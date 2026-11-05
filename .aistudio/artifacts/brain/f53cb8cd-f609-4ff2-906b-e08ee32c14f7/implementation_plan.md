# Rencana Implementasi: Modul Dongeng Petualangan Interaktif Bersama Petto 3D

## 1. Gambaran Umum Pengalaman Pengguna (UX)
Kami akan membangun **Modul Dongeng Petualangan Interaktif** berlayar penuh (*Full-Screen 3D Story Stage*) di mana **Petto 3D tampil langsung di atas panggung cerita** dan bereaksi secara ekspresif (*Takjub, Bersemangat, Penasaran, Berpikir, Senang*) sambil membacakan kisah petualangan yang dibuat oleh **Gemini AI** sesuai keputusan Anda.

---

## 2. Fitur Utama yang Akan Dibangun

### A. Layar Penuh Panggung Cerita 3D (`InteractiveStoryStageView.kt`)
- **Panggung Visual Bertema**: Latar belakang atmosferik yang berubah mengikuti tema cerita (*Hutan Kristal Ajaib*, *Misi Luar Angkasa*, *Misteri Kota Tua*, *Kerajaan Bawah Laut*, atau *Kastil Langit Naga*).
- **Petto 3D Live Narrator**: Karakter 3D tampil di bagian atas panggung dengan animasi bicara (*lip-sync*), ekspresi emosi dinamis sesuai suasana adegan (`sceneEmotion`), dan gerakan tubuh otomatis.
- **Kontrol Narasi Suara**: Tombol **Putar Ulang Suara Narasi**, indikator kalimat yang sedang dibacakan Petto, serta tombol kembali yang otomatis menyimpan progres cerita.

### B. Pilihan Cabang A/B + Aksi Bebas (Ketik atau Suara)
- **2 Tombol Pilihan Cepat (Pilihan A & Pilihan B)**: Dihasilkan secara kontekstual oleh Gemini AI pada setiap bab.
- **Input Aksi Bebas (Suara & Ketik Sendiri)**:
  - Pemain dapat mengetik aksi kreatif sendiri (contoh: *"Ajak naga itu makan ikan bakar"* atau *"Gunakan kunci kristal untuk membuka pintu rahasia"*) atau menekan tombol **🎙️ Bicara Aksi** untuk mendiktekan keputusan secara langsung.
  - Pemain juga bisa mengeklik item di **Tas Inventaris Ajaib** untuk menggunakan item tersebut di dalam cerita.

### C. Sistem Bab, Klimaks & Multi-Ending (`StoryChapter` & `StoryEndingType`)
- **Progres Bab (Bab 1 hingga Bab 5 - Klimaks/Ending)**:
  - Dilengkapi bilah progres bab (*Bab 1: Awal Petualangan* → *Bab 2-4: Konflik & Penjelajahan* → *Bab 5: Klimaks & Ending*).
  - Pemain juga dapat memilih untuk **Lanjut Bab Baru (Endless Mode)** setelah mencapai Ending jika masih ingin melanjutkan petualangan.
- **Tipe Ending Dinamis**: Gemini AI menentukan hasil akhir cerita berdasarkan keputusan pemain (*Ending Heroik / Legendaris*, *Ending Rahasia / Penemuan Langka*, atau *Ending Lucu / Tak Terduga*) lengkap dengan lencana pencapaian.

### D. Tas Inventaris Item Ajaib (`StoryItem`)
- Setiap bab dapat menghadiahkan **Item Ajaib / Petunjuk** baru yang ditemukan di dalam cerita (lengkap dengan ikon emoji, nama item, dan deskripsi singkat, misalnya `🗝️ Kunci Bintang Kuno` atau `🧪 Ramuan Cahaya Laut`).
- Baris **Inventaris Ajaib** ditampilkan di panggung cerita; pemain dapat mengetuk item untuk langsung menggunakannya pada giliran aksi berikutnya.

### E. Penyimpanan & Riwayat Petualangan (`StoryAdventureRepository.kt`)
- **Auto-Save & Resume**: Setiap bab otomatis tersimpan di penyimpanan lokal (`SharedPreferences` JSON).
- **Daftar Riwayat Petualangan**: Pemain dapat melanjutkan cerita yang belum tamat kapan saja, melihat daftar item yang sudah dikumpulkan, atau memulai petualangan baru.

---

## 3. Arsitektur & File yang Akan Dibuat / Diperbarui

1. **`app/src/main/java/com/example/model/FunActivityModels.kt`**:
   - Menambahkan model data `StoryItem`, `StoryEndingType`, dan `SavedStorySession`, serta memperkaya `StoryNode` dengan `chapterNumber`, `maxChapters`, `sceneEmotion`, `acquiredItem: StoryItem?`, `isEnding: Boolean`, `endingTitle: String?`, dan `endingType: StoryEndingType?`.
2. **`app/src/main/java/com/example/data/StoryAdventureRepository.kt`** *(File Baru)*:
   - Mengelola penyimpanan lokal untuk sesi petualangan aktif dan riwayat cerita tersimpan agar bisa dilanjutkan kapan saja.
3. **`app/src/main/java/com/example/ai/GeminiChatService.kt`**:
   - Memperbarui generator narasi cabang Gemini AI (`generateStoryNode`) agar menerima daftar **inventaris pemain**, **nomor bab saat ini**, **aksi bebas/pilihan pemain**, serta menghasilkan output terstruktur berisi judul bab, narasi cerita sesuai kepribadian Petto, emosi adegan, item baru yang ditemukan, status ending, dan Pilihan A/B.
4. **`app/src/main/java/com/example/ui/components/InteractiveStoryStageView.kt`** *(File Baru)*:
   - Antarmuka layar penuh **Panggung Dongeng Petualangan 3D** dengan kanvas Petto 3D, lencana Bab & Inventaris Item Ajaib, kartu narasi interaktif, tombol Pilihan A/B, kolom aksi bebas + mikrofon suara, serta kartu perayaan Ending.
5. **`app/src/main/java/com/example/ui/components/FunActivitiesSheet.kt`**, **`AvatarViewModel.kt`**, & **`AvatarRoomScreen.kt`**:
   - Menghubungkan tab **Dongeng** di menu **Aktivitas Seru** (dan tombol pintas langsung) untuk membuka **Layar Penuh Panggung Dongeng Petualangan 3D**, serta mengelola siklus hidup suara, emosi 3D, inventaris, dan penyimpanan cerita.
