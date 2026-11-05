# Rencana Implementasi Fitur Baru PawPal AI: Memori Jangka Panjang, Live Camera Vision, & Properti Ruangan Interaktif

Berdasarkan pilihan Anda, berikut adalah rencana arsitektur dan alur pengalaman pengguna untuk ketiga fitur yang akan ditambahkan:

---

## 1. Memori Jangka Panjang Karakter (*Long-Term Memory & Profil Memori*)
- **Ekstraksi & Penyimpanan Otomatis (`PetMemoryManager.kt`)**:
  - Setiap kali Anda mengobrol dengan karakter (melalui suara *Gemini Live*, *Video Call*, atau teks), sistem secara cerdas mendeteksi fakta penting tentang Anda (seperti **Nama Panggilan**, **Hobi/Minat**, **Makanan/Minuman Favorit**, **Pelajaran/Target Sekolah**, atau **Catatan Penting/Curhat Terakhir**) dan menyimpannya secara persisten di perangkat.
- **Konteks Personal di Semua Percakapan (`GeminiChatService.kt`)**:
  - Seluruh fakta memori yang tersimpan disuntikkan ke dalam instruksi sistem **Gemini 3.8 Flash** dan **Gemini 4 Argon**, sehingga karakter selalu mengingat nama dan cerita Anda saat menyapa atau menjawab pertanyaan.
- **Dialog Profil Memori yang Bisa Diedit (`MemoryProfileDialog.kt`)**:
  - Menambahkan tombol **Memori (`🧠`)** di *Top Bar* ruangan utama untuk membuka dialog **Memori Sahabat Petto**:
    - Menampilkan daftar hal-hal yang diingat Petto tentang Anda.
    - Memungkinkan Anda menambah fakta baru secara manual, mengedit profil nama panggilan, atau menghapus item memori tertentu kapan saja.

---

## 2. Penglihatan Kamera Langsung saat Video Call (*Live Camera Vision*)
- **Tangkapan Visual Otomatis & Tombol Manual (`FaceToFaceCallView.kt`)**:
  - **Otomatis saat Berbicara**: Ketika kamera depan/belakang pada kartu *PiP* aktif (`!isCameraMuted`), `PreviewView.bitmap` secara otomatis menangkap *snapshot* visual terbaru dan menyimpannya ke `AvatarViewModel` sehingga setiap kali Anda berbicara dalam *Video Call*, ucapan Anda dikirim bersama gambar kamera ke **Gemini 3.8 Flash Multimodal**.
  - **Tombol Manual "Lihat Kamera (`👁️ Lihat Aku`)"**: Menambahkan tombol aksi cepat pada layar *Video Call* dan pada kartu *Camera PiP* yang langsung menangkap gambar kamera saat itu juga dan meminta karakter mengomentari apa yang dilihatnya (ekspresi wajah Anda, benda yang Anda pegang, pakaian, atau buku).
- **Analisis Multimodal Real-Time (`GeminiChatService.kt`)**:
  - Memperbarui metode percakapan agar menerima `cameraBitmap: Bitmap?` opsional dan mengirimkannya sebagai `inlineData` (`image/jpeg`) ke Gemini 3.8 Flash / Firebase AI Logic sehingga karakter merespons secara ekspresif sesuai kepribadiannya.

---

## 3. Interaksi Ruangan & Properti 3D (*Interactive Room Props*)
- **Properti Ruangan Kontekstual per Ruangan (`InteractiveRoomPropsOverlay.kt` & `AvatarRoomScreen.kt`)**:
  - Menambahkan properti ruangan interaktif yang menyesuaikan dengan tema ruangan aktif dan dapat disentuh langsung:
    - **🛋️ Ruang Tamu (*Living Room*)**:
      - **Radio Musik Lo-Fi (`📻`)**: Menyalakan/mematikan alunan nada santai dengan gelombang nada mengambang dan membuat karakter bergoyang senang.
      - **Bola Mainan (`⚽`)**: Melempar/memantulkan bola untuk mengajak karakter melompat bermain.
    - **🛏️ Kamar Tidur (*Bedroom*)**:
      - **Saklar Lampu Tidur (`🏮`)**: Meredupkan pencahayaan ruangan menjadi suasana malam yang hangat dan menenangkan, memicu karakter menguap/rileks.
      - **Boneka Bintang (`🌟`)**: Memeluk/memainkan properti tidur yang membuat karakter merona senang.
    - **🍳 Dapur (*Kitchen*)**:
      - **Sajikan Camilan Segar (`🍓 / 🍔 / 🥛`)**: Memberikan camilan atau susu hangat langsung kepada karakter yang direspons dengan animasi makan/minum 3D dan komentar suara khas tiap karakter.
      - **Mesin Jus / Teko Hangat (`🍵`)**: Menyeduh minuman hangat dengan efek uap dan reaksi gembira dari karakter.
- **Reaksi Fisika & Suara Karakter 3D (`Avatar3DCanvas.kt` & `AvatarViewModel.kt`)**:
  - Setiap interaksi properti ruangan memicu gerakan 3D spesifik (melompat, mengunyah, bergoyang mengikuti musik, atau menguap nyaman), efek partikel kontekstual, serta komentar suara langsung dari karakter.
