# 🚀 EasyShare: Lightning-Fast Local File Transfer

![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)
![Platform: Windows | Android](https://img.shields.io/badge/Platform-Windows%20%7C%20Android-success.svg)

EasyShare is a robust, cross-platform ecosystem designed to transfer files between Windows PCs and Android devices over a local Wi-Fi network at maximum hardware speeds **(Use 5 GHz WiFi or Mobile hostspot to get faster file sharing. No internet, cables, or cloud storage required)**

This project was architected and built from scratch in a **30-hour sprint**, applying core engineering mindsets and leveraging AI pair-programming to optimize TCP socket performance and file I/O buffering.

---

## ✨ Key Features

* **⚡ Extreme Speeds:** Bypasses OS bottlenecks using `TCP_NODELAY`, 4MB Socket Buffers, and 1MB application-level read/write chunks.
* **🔌 Zero Cables, Zero Cloud:** Transfers data directly over your Local Area Network (LAN).
* **📱 Cross-Platform:** Seamlessly connect Windows (Server) and Android (Client).
* **🛑 Safe Cancellation:** Real-time transfer cancellation with automatic cleanup of partial/corrupted files.
* **📡 Auto-Discovery:** Devices find each other automatically over the network using UDP broadcasting.
* **📦 Batch Transfers:** Select and queue multiple files from your Android or PC file explorer.

---

## 🛠️ The Tech Stack

**Windows Client (Server)**
* **Language:** Python 3
* **UI Framework:** CustomTkinter
* **Networking:** Built-in `socket` and `threading` libraries

**Android Client**
* **Language:** Java
* **Environment:** Android Studio
* **Networking:** `java.net.Socket`, `DatagramSocket`, `ExecutorService`

---

## 📥 Installation & Usage

The easiest way to use EasyShare is to download the pre-compiled binaries from the Releases page.

### Option 1: Quick Install (Recommended)
1. Navigate to the **Releases Tab** on this repository.
2. Download `EasyShare.exe` for your Windows PC.
3. Download `EasyShare.apk` for your Android device and install it.
4. Ensure both devices are on the same Wi-Fi network. (You can also use mobile hotspot connection)
5. Open the Windows app and click **Start Service**.
6. Open the Android app, wait for it to auto-discover your PC, and start sharing!

### Option 2: Run from Source

**For Windows:**
```bash
git clone https://github.com/imjanindu/EasyShare.git
cd EasyShare/windows-client
pip install customtkinter
python app.pyw
```

**For Android:**
Open the `android-app` directory in **Android Studio**, sync the Gradle files, and run/build the project directly to your device.

---

## 📂 Repository Structure

```text
EasyShare/
├── android-app/          # Android Studio Project
├── windows-client/       # Windows PC Client
├── LICENSE               # MIT License
└── README.md             # Project Documentation
```

---

## 🚀 Future Roadmap (Version 2.0)
* **Peer-to-Peer (P2P) Architecture:** Transitioning from a Client-Server model to full P2P to allow Phone-to-Phone and PC-to-PC transfers.
* **UI Enhancements:** Implement a more user friendly and fluid like user interface.

---

## 👨‍💻 Author & Story

Built by **Janindu Malshan**.

* GitHub: [@imjanindu](https://github.com/imjanindu)
* LinkedIn: [Janindu Malshan](https://linkedin.com/in/imjanindu)

> *“I got tired of clunky, ad-filled file transfer apps and slow cloud uploads. Instead of complaining, I applied an engineering mindset to learn the fundamentals of network sockets, OS buffering, and I/O streams to build my own solution in 3 days.”*