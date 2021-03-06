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

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ConfigurationInfo;
import android.graphics.SurfaceTexture;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.util.AttributeSet;

import java.io.File;
import java.util.List;

import static to.augmented.reality.android.common.sensor.orientation.OrientationProvider.ORIENTATION_PROVIDER;

public class ARSurfaceView extends GLSurfaceView
//==============================================
{
   private static final String TAG = ARSurfaceView.class.getSimpleName();

   private GLRecorderRenderer renderer = null;
   RecorderActivity activity = null;
   private SurfaceTexture previewSurface;
   public SurfaceTexture getPreviewSurface() { return previewSurface; }
   protected void setPreviewSurface(SurfaceTexture previewSurface) { this.previewSurface = previewSurface; }

   boolean isES2 = false, isES3 = false;
//   private final SurfaceHolder holder;

   public ARSurfaceView(final Context activity) { this(activity, null, 0); }

   public ARSurfaceView(final Context activity, AttributeSet attrs) { this(activity, attrs, 0); }

   public ARSurfaceView(final Context activity, AttributeSet attrs, int defStyle)
   //-----------------------------------------------------------------------
   {
      super(activity, attrs);
      if (! isInEditMode())
      {
         this.activity = (RecorderActivity) activity;
         final ActivityManager activityManager = (ActivityManager) activity.getSystemService(Context.ACTIVITY_SERVICE);
         final ConfigurationInfo configurationInfo = activityManager.getDeviceConfigurationInfo();
         isES2 = configurationInfo.reqGlEsVersion >= 0x20000
               || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH_MR1
               && (Build.FINGERPRINT.startsWith("generic")
               || Build.FINGERPRINT.startsWith("unknown")
               || Build.MODEL.contains("google_sdk")
               || Build.MODEL.contains("Emulator")
               || Build.MODEL.contains("Android SDK built for x86")));
         isES3 = configurationInfo.reqGlEsVersion >= 0x30000
               || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2
               && (Build.FINGERPRINT.startsWith("generic")
               || Build.FINGERPRINT.startsWith("unknown")
               || Build.MODEL.contains("google_sdk")
               || Build.MODEL.contains("Emulator")
               || Build.MODEL.contains("Android SDK built for x86")));
         isES3 = false;
         if (isES3)
         {
            setEGLContextClientVersion(3);
            isES2 = false;
         } else if (isES2)
            setEGLContextClientVersion(2);
         else
            throw new RuntimeException("Outdated hardware. OpenGL ES2 or 3 is required");

         setEGLConfigChooser(8, 8, 8, 8, 16, 0);
         setDebugFlags(GLSurfaceView.DEBUG_CHECK_GL_ERROR | GLSurfaceView.DEBUG_LOG_GL_CALLS);
         renderer = new GLRecorderRenderer((RecorderActivity) activity, this, ORIENTATION_PROVIDER.DEFAULT);
         setRenderer(renderer);
         setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
      }
   }

   @Override
   public void onPause()
   //--------------------
   {
      super.onPause();
      if (renderer != null)
         renderer.pause();
   }

   public void onSaveInstanceState(Bundle B)
   //---------------------------------------
   {
      B.putBoolean("isES2", isES2);
      B.putBoolean("isES3", isES3);
      renderer.onSaveInstanceState(B);
   }

   @Override
   public void onResume()
   //-------------------
   {
      super.onResume();
   }

   public void onRestoreInstanceState(Bundle B)
   //------------------------------------------
   {
      isES2 = B.getBoolean("isES2");
      isES3 = B.getBoolean("isES3");
      renderer.onRestoreInstanceState(B);
   }


//   @Override
//   public boolean onTouchEvent(MotionEvent event)
//   {
//      boolean b = super.onTouchEvent(event);
//      boolean bb = b && activity.onPreviewTouch(event);
//      return bb;
//   }

   public GLRecorderRenderer getRenderer() { return renderer; }

   public void startPreview(int width, int height, boolean isFlashOn, boolean useCamera2Api)
   //-----------------------------------------------------------------------------------------
   {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
      {
         if (! activity.hasPermissions(RecorderActivity.ESSENTIAL_PERMISSIONS))
         {
            activity.requestPermissions(RecorderActivity.ESSENTIAL_PERMISSIONS, 1);
            return;
         }
         if (! activity.hasPermissions(RecorderActivity.OPTIONAL_PERMISSIONS))
            activity.requestPermissions(RecorderActivity.OPTIONAL_PERMISSIONS, 2);
      }
      if (renderer != null)
         renderer.startPreview(width, height, isFlashOn, useCamera2Api);
   }

   public boolean isPreviewing() { return (renderer != null) && renderer.isPreviewing(); }

   public boolean hasFlash() { return (renderer != null) && renderer.hasFlash(); }

   public boolean isRecording() { return (renderer != null) && renderer.isRecording; }

   public boolean isStoppingRecording() { return (renderer != null) && renderer.isStopRecording; }

   public boolean startRecording(File dir, int width, int height, String name, float increment, long maxsize,
                                 RecordingThread.RecordingType recordingType, ORIENTATION_PROVIDER orientationType,
                                 List<Integer> xtraSensorList, boolean isDebug, boolean isFlashOn, boolean useCamera2Api)
   //-----------------------------------------------------------------------------------------------
   {
      return renderer.startRecording(dir, width, height, name, increment, maxsize, recordingType, orientationType,
                                     xtraSensorList, isDebug, isFlashOn, useCamera2Api);
   }

   public void stopRecording(final boolean isCancelled) { renderer.stopRecording(isCancelled); }

   void stopRecordingFlag() { renderer.stopRecordingFlag(); }

   public void pauseRecording() { renderer.pauseRecording(); }

   public void resumeRecording() { renderer.resumeRecording(); }

   public boolean initOrientationSensor(String orientationType, StringBuilder errbuf)
   //--------------------------------------------------------------------------------
   {
      ORIENTATION_PROVIDER orientationProviderType = ORIENTATION_PROVIDER.valueOf(orientationType);
      return renderer.initOrientationSensor(orientationProviderType, errbuf);
   }
}
