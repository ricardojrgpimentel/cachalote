#!/system/bin/sh
# Arranque automático do cachalote — instalar em /data/adb/service.d/cachalote.sh
# O Magisk corre os scripts de service.d no arranque, como root, em late_start.
BASE="${CACHALOTE_BASE:-/data/adb/cachalote}"

# Dar tempo ao sistema (rede, /data montado, zygote) antes de subir o motor.
sleep 20

# O watchdog (não um `start` único): se o daemon morrer por pressão de memória, ele volta.
# Corre em segundo plano para não segurar o arranque.
if [ -x "$BASE/bin/cachalote" ]; then
  "$BASE/bin/cachalote" start >>"$BASE/logs/boot.log" 2>&1
  ( "$BASE/bin/cachalote" watchdog 30 >>"$BASE/logs/boot.log" 2>&1 & )
fi
