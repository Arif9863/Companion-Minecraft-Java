# Pindah server companion ke PC baru

Dokumen ini menjelaskan cara memindahkan server Companion ke PC/laptop Windows lain.
Perubahan dan update script tetap dilakukan di PC lama (G:\GameServerJava); PC baru
hanya untuk menjalankan server.

## Apa yang sudah ada di GitHub (otomatis ikut clone)

- Source plugin CompanionNPCs (`plugin/src/main/java`, `plugin/src/main/resources`)
- JAR plugin hasil build: `plugin/build/companion-npcs-1.0.0.jar` — siap dipasang tanpa kompilasi
- Dua skrip Skript companion (`yui_companion.sk`, `sandrone_companion.sk`)
- 30 file suara `.ogg` (`resource-pack-source/`)
- Skin Yui & Sandrone + `pack.mcmeta`
- `build-plugin.ps1` dan `rebuild-pack.ps1` (keduanya portabel)

## Apa yang TIDAK ada di GitHub (harus disalin manual)

| Yang perlu | Kenapa |
|---|---|
| `paper.jar` | Binari server, 55 MB |
| `server.properties` | Berisi secret, sengaja tidak di-commit |
| `plugins/Skript-2.17.1.jar` | Plugin pihak ketiga |
| `plugins/Skript/config.sk` | Setelan suara & namespace |
| `plugins/Skript/features.sk` | Setelan fitur Skript |
| `plugins/CompanionNPCs/config.yml` | Dihasilkan otomatis saat server pertama jalan |

## Langkah

### 1. Clone repo di PC baru

```bash
git clone https://github.com/Arif9863/Companion-Minecraft-Java.git
cd Companion-Minecraft-Java
```

### 2. Salin file besar & sensitif dari PC lama

Dari PC lama, salin ke USB/cloud, lalu taruh di PC baru:

```
paper.jar                              -> Paper/Paper26.3/paper.jar
server.properties                      -> Paper/Paper26.3/server.properties
plugins/Skript-2.17.1.jar              -> Paper/Paper26.3/plugins/
plugins/Skript/config.sk               -> Paper/Paper26.3/plugins/Skript/
plugins/Skript/features.sk             -> Paper/Paper26.3/plugins/Skript/
```

`libraries/` (74 MB) tidak perlu disalin: Paper mengunduhnya sendiri saat pertama jalan.

### 3. Pasang plugin Companion di PC baru

```bash
powershell -NoProfile -ExecutionPolicy Bypass -File ./build-plugin.ps1
```

Skrip ini menyalin JAR ke `Paper/Paper26.3/plugins/`. Kalau folder `libraries/`
Paper belum ada (kondisi normal pada clone murni) dan Maven tidak terpasang, skrip
tidak mengompilasi — ia **memakai JAR yang sudah ikut ter-clone** lalu menyalinnya.
Begitu `libraries/` sudah tersedia, skrip kembali mengompilasi source langsung.

**Penting:** JAR di Git hanya ikut ter-update bila kamu membangun dan meng-commit
di PC lama. Setiap kali mengubah source Java, jalankan `build-plugin.ps1`, lalu
`git add` + `commit` + `push` — termasuk `plugin/build/companion-npcs-1.0.0.jar`.

### 4. Bangun resource pack di PC baru

```bash
powershell -NoProfile -ExecutionPolicy Bypass -File ./rebuild-pack.ps1
```

Script menyalin suara, menulis `sounds.json`, membuat ZIP, dan menyalinnya ke
`%APPDATA%\.minecraft\resourcepacks\`. Kalau client Minecraft tidak ada di PC itu,
langkah "Salin zip ke client Minecraft" akan gagal — abaikan, ZIP di server tetap jadi.

### 5. Jalankan server

Double-click `Paper/Paper26.3/start.bat`, atau:

```bash
cd Paper/Paper26.3
java -Xms2G -Xmx4G -jar paper.jar nogui
```

## Catatan penting

- **Dunia baru.** `world/` sengaja tidak ikut Git. PC baru akan membuat dunia baru
  otomatis. Kalau mau dunia lama, salin folder `world/` manual.
- **Port.** `server.properties` memakai `server-port=25565`. Kalau PC lama dan PC baru
  jalan bersamaan, ganti port di PC baru (misal `25566`) supaya tidak bentrok.
- **Resource pack.** `resource-pack` di `server.properties` menunjuk ke
  `http://localhost:8765/...`. Plugin CompanionNPCs menyalakan server HTTP kecil di
  port 8765, jadi pack otomatis terunduh oleh client yang terhubung ke PC baru.
- **Perubahan tetap di PC lama.** Setelah edit di PC lama, jalankan `git push` di PC
  lama, lalu di PC baru `git pull` untuk menyinkronkan.

## Kalau mau dunia lama ikut

Salin `Paper/Paper26.3/world/` dari PC lama ke PC baru **sebelum** server pertama kali
dijalankan. Hentikan server di PC lama dulu supaya world tidak sedang ditulis.
