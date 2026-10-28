#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
mkdir -p app/src/main/res/raw

# Eight-second periodic color motion, straight grid, and unique orientation markers.
ffmpeg -y -f lavfi -i "nullsrc=s=1280x720:r=30,format=gbrp,geq=r='75+40*sin(2*PI*(X/W+Y/H+T/8))':g='100+70*sin(2*PI*(X/W-Y/H-T/8))':b='180+40*cos(2*PI*(2*X/W+Y/H+T/8))',drawgrid=width=160:height=90:thickness=2:color=white@0.40,drawbox=x=44:y=16:w=40:h=40:color=0x67E8F9:t=fill,drawbox=x=1196:y=16:w=40:h=40:color=0xA78BFA:t=fill,drawbox=x=1196:y=664:w=40:h=40:color=0xFBBF24:t=fill,drawbox=x=44:y=664:w=40:h=40:color=0x34D399:t=fill" \
    -t 8 -an -c:v libx264 -preset medium -crf 18 -pix_fmt yuv420p \
    -movflags +faststart app/src/main/res/raw/prism_waves.mp4
