set -eu
mkdir -p build/classes build/dex
python3 - <<'PY'
import zipfile,pathlib
with zipfile.ZipFile('vendor/platform.zip') as z:
 name=next(n for n in z.namelist() if n.endswith('/android.jar'))
 pathlib.Path('build/android.jar').write_bytes(z.read(name))
PY
javac -source 11 -target 11 -cp build/android.jar -d build/classes src/*.java
jar cf build/helper-classes.jar -C build/classes .
java -cp vendor/r8.jar com.android.tools.r8.D8 --lib build/android.jar --min-api 30 --output build/dex build/helper-classes.jar
cd build/dex
zip -q ../dsh-vdisplay.jar classes.dex
