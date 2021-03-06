/*
* Copyright (C) 2014 Donald Munro.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*     http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package to.augmented.reality.android.em.recorder;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.hardware.camera2.CameraAccessException;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.ConditionVariable;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicYuvToRGB;
import android.renderscript.Type;
import android.util.Log;
import to.augmented.reality.em.recorder.ScriptC_yuv2grey;

import java.io.File;
import java.io.IOException;
import java.util.List;

public class LegacyPreviewCamera implements Previewable, Bufferable
//=================================================================
{
   static final private String LOGTAG = "LegacyPreviewCamera";

   private final GLRecorderRenderer renderer;
   private final GLSurfaceView surfaceView;
   private final ConditionVariable frameAvailCondVar;
   private final int bufferSize;
   private int cameraId = -1, cameraWidth = -1, cameraHeight = -1, format = -1;
   private Camera camera;
   private String[] resolutions = new String[0];
   private CameraPreviewThread previewThread = null;
   private NativeFrameBuffer frameBuffer = null;
   private RenderScript renderscript = null;
   private float focalLen = -1;
   private boolean isFlashOn = false;
   @Override public boolean isFlashOn() { return isFlashOn; }
   private boolean hasFlash = false;
   @Override public boolean hasFlash() { return hasFlash; }

   public float getFocalLen() { return focalLen; }

   float fovx = -1;
   public float getFovx() { return fovx; }

   float fovy = -1;
   public float getFovy() { return fovy; }


   public LegacyPreviewCamera(GLRecorderRenderer renderer, GLSurfaceView surfaceView, int bufferSize,
                              NativeFrameBuffer frameBuffer, ConditionVariable frameAvailCondVar)
   //----------------------------------------------------------------------------------------------------------------
   {
      this.renderer = renderer;
      this.surfaceView = surfaceView;
      this.frameBuffer = frameBuffer;
      this.bufferSize = bufferSize;
      this.frameAvailCondVar = frameAvailCondVar;
      renderscript = RenderScript.create(renderer.activity);
   }

   @Override
   public boolean open(int facing, int width, int height, StringBuilder errbuf) throws CameraAccessException
   //-------------------------------------------------------------------------------------------------------
   {
      Camera.CameraInfo cameraInfo = new Camera.CameraInfo();
      for (int i = 0; i < Camera.getNumberOfCameras(); i++)
      {
         Camera.getCameraInfo(i, cameraInfo);
         if (cameraInfo.facing == facing)
         {
            cameraId = i;
            try { camera = Camera.open(cameraId); } catch (Exception _e) { camera = null; }
            break;
         }
      }
      if (camera == null)
      {
         if (errbuf != null) errbuf.append("Error acquiring camera");
         return false;
      }

      Camera.Parameters cameraParameters = camera.getParameters();
      if (format < 0)
         format = cameraParameters.getPreviewFormat();
      List<Camera.Size> resolutions = cameraParameters.getSupportedPreviewSizes();
      int bestWidth = 0, bestHeight = 0;
      float aspect = (float) width / height;
      this.resolutions = new String[resolutions.size()];
      int i = 0;
      for (Camera.Size resolution : resolutions)
      {
         int w = resolution.width, h = resolution.height;
         this.resolutions[i++] = String.format("%dx%d", w, h);
         if ( (width >= w) && (height >= h) && (bestWidth <= w) && (bestHeight <= h) &&
              (Math.abs(aspect - (float) w / h) < 0.2) )
         {
            bestWidth = w;
            bestHeight = h;
         }
      }
      Log.i(LOGTAG, "best size: " + bestWidth + "x" + bestHeight);
      if ( (bestWidth == 0) || (bestHeight == 0) )
      {
         if (errbuf != null) errbuf.append("Error finding matching preview size for ").append(width).append('x').
                                    append(height);
         Log.e(LOGTAG, "Error finding matching preview size");
         return false;
      }
      cameraWidth = bestWidth;
      cameraHeight = bestHeight;

      if (cameraParameters.isVideoStabilizationSupported())
         cameraParameters.setVideoStabilization(true);
      List<String> flashModes = cameraParameters.getSupportedFlashModes();
      hasFlash = ( (flashModes != null) && (flashModes.contains(Camera.Parameters.FLASH_MODE_TORCH)) );
      List<String> focusModes = cameraParameters.getSupportedFocusModes();
      if (focusModes != null && focusModes.contains(Camera.Parameters.FOCUS_MODE_INFINITY))
         cameraParameters.setFocusMode(Camera.Parameters.FOCUS_MODE_INFINITY);
      if (cameraParameters.isZoomSupported())
         cameraParameters.setZoom(0);
      fovx = cameraParameters.getHorizontalViewAngle();
      fovy = cameraParameters.getVerticalViewAngle();
      focalLen = cameraParameters.getFocalLength();
      int[] fps = new int[2];
      cameraParameters.getPreviewFpsRange(fps);
      cameraParameters.setPreviewFpsRange(fps[1], fps[1]);
      camera.setParameters(cameraParameters);

      final int inputFormat = ImageFormat.NV21;
      previewThread = new CameraPreviewThread(renderer, camera, cameraWidth, cameraHeight, bufferSize, format,
                                              frameBuffer, frameAvailCondVar);
      previewThread.start();
      return true;
   }

   @Override
   public void close()
   //-----------------
   {
      if (previewThread != null)
         previewThread.stopPreview();
      if (camera != null)
      {
         try { camera.release(); } catch (Exception _e) { Log.e(LOGTAG, _e.getMessage()); }
      }
      camera = null;
   }

   @Override
   public void startPreview(boolean isFlashOn)
   //-----------------------------------------
   {
      if ( (camera != null) && (previewThread != null) )
      {
         this.isFlashOn = isFlashOn;
         toggleFlash(isFlashOn);
         previewThread.startPreview();
      }
      else
         throw new RuntimeException("previewThread not initialized");
   }

   public void setFlash(boolean isOnOff) { isFlashOn = isOnOff; }

   public void toggleFlash(boolean isOn)
   //-----------------------------------
   {
      if (camera == null)
         return;
      try
      {
         Camera.Parameters cameraParameters = camera.getParameters();
         List<String> flashModes = cameraParameters.getSupportedFlashModes();
         String mode;
         if (isOn)
         {
            if (flashModes.contains(Camera.Parameters.FLASH_MODE_TORCH))
               mode = Camera.Parameters.FLASH_MODE_TORCH;
            else
               mode = Camera.Parameters.FLASH_MODE_ON;
         }
         else
            mode = Camera.Parameters.FLASH_MODE_OFF;
         cameraParameters.setFlashMode(mode);
         camera.setParameters(cameraParameters);
      }
      catch (Exception e)
      {
         Log.e(LOGTAG, "", e);
      }
   }

   @Override
   public void stopPreview()
   //----------------------
   {
      if (previewThread != null)
      {
         isFlashOn = false;
         toggleFlash(isFlashOn);
         previewThread.stopPreview();
      }
   }

   @Override public void suspendPreview()
   //------------------------------------
   {
      if (previewThread != null)
      {
         toggleFlash(false);
         previewThread.suspendPreview();
      }
   }

   @Override public void restartPreview()
   //------------------------------------
   {
      if (previewThread != null)
      {
         toggleFlash(isFlashOn);
         previewThread.restartPreview();
      }
   }


   @Override
   public boolean isPreviewing() { return (previewThread == null) ? false : previewThread.isPreviewing(); }

   @Override public void releaseFrame() { if (previewThread != null) previewThread.releaseFrame(); }

   @Override public int getPreviewWidth() { return cameraWidth;}

   @Override public int getPreviewHeight() { return cameraHeight; }

   @Override public void bufferOn() { if (previewThread != null) previewThread.bufferOn(); }

   @Override public void bufferOff() { if (previewThread != null) previewThread.bufferOff(); }

   @Override public void bufferClear() { frameBuffer.bufferClear(); }

   @Override public boolean bufferEmpty() { return frameBuffer.bufferEmpty(); }

   @Override public void writeFile(File f) throws IOException { frameBuffer.writeFile(f); }

   @Override public File writeFile() { return frameBuffer.writeFile(); }

   @Override public boolean closeFile() throws IOException { return frameBuffer.closeFile(); }

   @Override public void flushFile() { frameBuffer.flushFile(); }

   @Override public void writeOff() { frameBuffer.writeOff(); }

   @Override public void writeOn() { frameBuffer.writeOn(); }

   @Override public void push(long timestamp, byte[] data) { frameBuffer.push(timestamp, data); }

   @Override public void startTimestamp(long timestamp) { frameBuffer.startTimestamp(timestamp); }

   @Override public int writeCount() { return frameBuffer.writeCount(); }

   @Override public long writeSize() { return frameBuffer.writeSize(); }

   @Override public boolean openForReading() { return frameBuffer.openForReading(); }

   @Override public boolean openForReading(File f) { return frameBuffer.openForReading(f); }

   @Override public BufferData read() throws IOException { return frameBuffer.read(); }

   @Override public long readPos(long offset) { return frameBuffer.readPos(offset); }

   @Override public void stop() { frameBuffer.stop(); }

   @Override public PreviewData pop() { throw new RuntimeException("N/A (NativeFrameBuffer writes data)"); }

   @Override public String getPreviewFormat() { return "NV21"; }

   @Override public String[] availableResolutions() { return resolutions; }

   @Override public int getPreviewBufferSize() { return frameBuffer.getFrameSize(); }

   @Override public void setPreviewWidth(int width) { cameraWidth = width; }

   @Override public  void setPreviewHeight(int height) { cameraHeight = height; }

   @Override
   public byte[] toRGBA(Context context, byte[] frame, int previewWidth, int previewHeight, int rgbaSize, byte[] grey)
   //-----------------------------------------------------------------------------------------------------------------
   {
      ScriptIntrinsicYuvToRGB YUVToRGB = null;
      byte[] rgbaBuffer = null;
      try
      {
         Type.Builder yuvType = new Type.Builder(renderscript, Element.U8(renderscript)).setX(previewWidth).
               setY(previewHeight).setMipmaps(false);
         yuvType.setYuvFormat(ImageFormat.NV21);
         Allocation ain = Allocation.createTyped(renderscript, yuvType.create(), Allocation.USAGE_SCRIPT);

         Type.Builder rgbType = null;
         YUVToRGB = ScriptIntrinsicYuvToRGB.create(renderscript, Element.U8_4(renderscript));
         rgbType = new Type.Builder(renderscript, Element.RGBA_8888(renderscript));
         rgbaBuffer = new byte[rgbaSize];
         rgbType.setX(previewWidth).setY(previewHeight).setMipmaps(false);
         Allocation aOut = Allocation.createTyped(renderscript, rgbType.create(), Allocation.USAGE_SCRIPT);
         Allocation allocGrayOut = null;
         ScriptC_yuv2grey rsYUVtoGrey = null;
         if (grey != null)
         {
            Type.Builder greyTypeBuilder = new Type.Builder(renderscript, Element.U8(renderscript));
            greyTypeBuilder.setX(previewWidth).setY(previewHeight);
            allocGrayOut = Allocation.createTyped(renderscript, greyTypeBuilder.create(), Allocation.USAGE_SCRIPT);
            rsYUVtoGrey = new ScriptC_yuv2grey(renderscript);
            rsYUVtoGrey.set_in(ain);
         }
         ain.copyFrom(frame);
         YUVToRGB.setInput(ain);
         YUVToRGB.forEach(aOut);
         aOut.copyTo(rgbaBuffer);
         if (grey != null)
         {
            rsYUVtoGrey.forEach_yuv2grey(allocGrayOut);
            allocGrayOut.copyTo(grey);
         }
         return rgbaBuffer;
      }
      catch (Exception e)
      {
         Log.e(LOGTAG, "", e);
         return null;
      }
   }


   @Override
   public void freeze(Bundle B)
   //-------------------------
   {
      if (previewThread != null)
         previewThread.freeze(B);
      B.putInt("cameraId", cameraId);
      B.putInt("cameraWidth", cameraWidth);
      B.putInt("cameraHeight", cameraHeight);
   }

   @Override
   public void thaw(Bundle B)
   //--------------------------
   {
      if (previewThread != null)
         previewThread.thaw(B);
      cameraId = B.getInt("cameraId");
      cameraWidth = B.getInt("cameraWidth");
      cameraHeight = B.getInt("cameraHeight");
   }
}
