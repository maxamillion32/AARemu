LIBS=`pkg-config --libs opencv`
filename=$(basename "$1")
extension="${filename##*.}"
filename="${filename%.*}"
if [ "$filename" = "$extension" ]
then
   extension="cc"
fi

g++ -Wall -std=c++11 -I/usr/include $filename.$extension -o $filename $LIBS
