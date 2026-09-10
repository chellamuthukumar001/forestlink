OFFLINE MAP INSTRUCTIONS
========================

To bundle an offline map with this application:

1. Download or generate an offline map file for your region.
   Supported formats: .mbtiles, .zip (tiles), .sqlite, .gemf

2. Place the file (e.g., "bengaluru.mbtiles" or "forest_map.zip") in this folder:
   app/src/main/assets/

3. Rebuild and install the app.

The app will automatically detect this file on the first launch and copy it to the 
app's private storage for offline usage.

Note: 
- Large map files (100MB+) will increase the APK size significantly.
- If using a .zip file, it should contain the tile directory structure (zoom/x/y.png).
