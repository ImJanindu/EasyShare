import customtkinter as ctk
import socket
import threading
import os
import time
import webbrowser
import ctypes
import configparser
from pathlib import Path
from tkinter import messagebox 

# --- CONFIGURATION ---
BROADCAST_PORT = 5005
PRESENCE_PORT = 5006
FILE_PORT = 6000
RECEIVE_PORT = 5001
CONFIG_FILE = "config.ini"

# --- SPEED CONFIGURATION ---
CHUNK_SIZE = 1024 * 1024      # 1MB Loop Buffer
SOCKET_BUFFER = 4 * 1024 * 1024 # 4MB OS Socket Buffer

# --- 1. FIX TASKBAR ICON GROUPING ---
try:
    myappid = 'janindu.easyshare.pc.version.1.0' 
    ctypes.windll.shell32.SetCurrentProcessExplicitAppUserModelID(myappid)
except:
    pass

ctk.set_appearance_mode("dark")
ctk.set_default_color_theme("blue")

class EasyShareServer(ctk.CTk):
    def __init__(self):
        super().__init__()
        
        # --- WINDOW SETUP ---
        self.title("EasyShare PC")
        self.geometry("400x700") 
        self.resizable(False, False)
        
        # Fonts
        self.font_title = ctk.CTkFont(family="Ubuntu", size=24, weight="bold")
        self.font_name = ctk.CTkFont(family="Ubuntu", size=18, weight="bold") 
        self.font_header = ctk.CTkFont(family="Ubuntu", size=16, weight="bold")
        self.font_button = ctk.CTkFont(family="Ubuntu", size=15, weight="bold") 
        self.font_status = ctk.CTkFont(family="Ubuntu", size=14) 
        self.font_console = ctk.CTkFont(family="Consolas", size=11)

        try: self.iconbitmap("icon.ico")
        except: pass

        self.server_socket = None
        self.presence_socket = None
        self.is_running = False
        self.mobile_ip = None 
        self.last_seen = 0
        self.is_cancelled = False
        self.is_transferring = False 
        
        # LOAD SAVED NAME
        self.pc_name = self.load_config_name()
        
        # --- UI LAYOUT ---
        self.title_label = ctk.CTkLabel(self, text="EasyShare PC", font=self.font_title)
        self.title_label.pack(pady=(25, 10))

        # Device Name
        self.name_frame = ctk.CTkFrame(self, fg_color="transparent")
        self.name_frame.pack(pady=(0, 25))
        
        self.name_label = ctk.CTkLabel(self.name_frame, text=self.pc_name, font=self.font_name, text_color="#4CAF50")
        self.name_label.pack(side="left", padx=(0, 10))
        
        self.rename_btn = ctk.CTkButton(self.name_frame, text="✎", width=30, height=30, 
                                        font=("Arial", 16), fg_color="#333333", hover_color="#555555",
                                        command=self.show_rename_dialog)
        self.rename_btn.pack(side="left")

        # Status Card
        self.status_frame = ctk.CTkFrame(self, fg_color="#2B2B2B", corner_radius=10)
        self.status_frame.pack(fill="x", padx=20, pady=5)

        self.status_label = ctk.CTkLabel(self.status_frame, text="Service Stopped", font=self.font_header, text_color="#9E9E9E")
        self.status_label.pack(pady=(15, 5))

        self.toggle_btn = ctk.CTkButton(self.status_frame, text="Start Service", font=self.font_button, height=45, command=self.toggle_server)
        self.toggle_btn.pack(pady=(5, 15), padx=20, fill="x")

        # Action Card
        self.action_frame = ctk.CTkFrame(self, fg_color="transparent")
        self.action_frame.pack(fill="x", padx=20, pady=10)

        self.send_btn = ctk.CTkButton(self.action_frame, text="Select File to Send", font=self.font_button, height=55, 
                                      state="disabled", fg_color="#444444", command=self.select_files_to_send)
        self.send_btn.pack(fill="x", pady=(0, 10))

        # Cancel Button
        self.cancel_btn = ctk.CTkButton(self.action_frame, text="Cancel Transfer", font=self.font_button, height=40,
                                        state="disabled", fg_color="#C62828", hover_color="#B71C1C", command=self.cancel_transfer)
        self.cancel_btn.pack(fill="x")
        
        # Progress
        self.progress_label = ctk.CTkLabel(self, text="Ready", font=self.font_status, text_color="#BBBBBB")
        self.progress_label.pack(pady=(15, 0))
        
        self.progress_bar = ctk.CTkProgressBar(self, width=360, height=10)
        self.progress_bar.set(0)
        self.progress_bar.pack(pady=5)

        # Logs
        self.console = ctk.CTkTextbox(self, width=360, height=100, font=self.font_console)
        self.console.pack(pady=10, fill="both", expand=True, padx=20)
        self.console.configure(state="disabled")

        # About Button
        self.about_btn = ctk.CTkButton(self, text="About Developer", width=140, height=35, 
                                       font=self.font_button, fg_color="#333333", hover_color="#444444", 
                                       text_color="white", command=self.show_about_dialog)
        self.about_btn.pack(side="bottom", pady=15)

        # Handle Window Close Event
        self.protocol("WM_DELETE_WINDOW", self.on_close)

        threading.Thread(target=self.monitor_connection, daemon=True).start()
        
        # --- AUTO START SERVER ---
        self.after(500, self.toggle_server)

    # --- HELPERS ---
    def get_size_str(self, size):
        for unit in ['B', 'KB', 'MB', 'GB']:
            if size < 1024.0:
                return f"{size:.1f} {unit}"
            size /= 1024.0
        return f"{size:.1f} TB"

    def cancel_transfer(self):
        if messagebox.askyesno("Confirm Cancel", "Are you sure you want to stop the transfer?"):
            self.is_cancelled = True
            self.cancel_btn.configure(state="disabled")
            self.log("[!] Cancelling transfer...")

    # --- SAFE EXIT HANDLER ---
    def on_close(self):
        if self.is_transferring:
            if not messagebox.askyesno("Transfer in Progress", "A file transfer is currently in progress. Are you sure you want to exit?"):
                return 
                
        if self.is_running:
            self.toggle_server() 
        self.destroy() 

    # --- CONFIG MANAGEMENT ---
    def load_config_name(self):
        config = configparser.ConfigParser()
        if os.path.exists(CONFIG_FILE):
            config.read(CONFIG_FILE)
            if "Settings" in config and "PCName" in config["Settings"]:
                return config["Settings"]["PCName"]
        return socket.gethostname()

    def save_config_name(self, new_name):
        config = configparser.ConfigParser()
        config["Settings"] = {"PCName": new_name}
        with open(CONFIG_FILE, "w") as f:
            config.write(f)

    # --- CUSTOM RENAME DIALOG ---
    def show_rename_dialog(self):
        w, h = 300, 150
        x = self.winfo_x() + (self.winfo_width() // 2) - (w // 2)
        y = self.winfo_y() + (self.winfo_height() // 2) - (h // 2)
        top = ctk.CTkToplevel(self)
        top.title("Rename PC")
        top.geometry(f"{w}x{h}+{x}+{y}")
        top.resizable(False, False)
        top.attributes("-topmost", True)
        try: top.after(200, lambda: top.iconbitmap("icon.ico"))
        except: pass
        ctk.CTkLabel(top, text="Enter New Name:", font=("Ubuntu", 14)).pack(pady=(15, 5))
        entry = ctk.CTkEntry(top, width=200)
        entry.insert(0, self.pc_name)
        entry.pack(pady=5)
        def save_name():
            new_name = entry.get().strip()
            if new_name:
                self.pc_name = new_name
                self.save_config_name(self.pc_name)
                self.name_label.configure(text=self.pc_name)
                self.log(f"[*] PC Renamed to: {self.pc_name}")
                top.destroy()
        ctk.CTkButton(top, text="Save", width=100, command=save_name).pack(pady=10)

    # --- SMART IP FILTER ---
    def get_valid_ips(self):
        valid_ips = []
        try:
            host_name = socket.gethostname()
            all_ips = socket.gethostbyname_ex(host_name)[2]
            for ip in all_ips:
                if ip.startswith("127."): continue
                if ip.startswith("169.254"): continue
                if ip.startswith("192.168.56"): continue
                if ip.startswith("192.168.111") or ip.startswith("192.168.233"): continue
                valid_ips.append(ip)
        except: pass
        if not valid_ips:
            try:
                s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                s.connect(("10.255.255.255", 1))
                ip = s.getsockname()[0]
                s.close()
                if not ip.startswith("127."): valid_ips.append(ip)
            except: pass
        return valid_ips

    # --- ABOUT DIALOG ---
    def show_about_dialog(self):
        w, h = 350, 260
        x = self.winfo_x() + (self.winfo_width() // 2) - (w // 2)
        y = self.winfo_y() + (self.winfo_height() // 2) - (h // 2)
        top = ctk.CTkToplevel(self)
        top.title("About")
        top.geometry(f"{w}x{h}+{x}+{y}")
        top.resizable(False, False)
        top.attributes("-topmost", True)
        try: top.after(200, lambda: top.iconbitmap("icon.ico"))
        except: pass
        ctk.CTkLabel(top, text="EasyShare PC", font=("Ubuntu", 20, "bold")).pack(pady=(25, 5))
        ctk.CTkLabel(top, text="Version 1.0 (Stable)", font=("Ubuntu", 12)).pack()
        ctk.CTkLabel(top, text="Developed by Janindu Malshan", font=("Ubuntu", 14, "bold")).pack(pady=(15, 5))
        btn_frame = ctk.CTkFrame(top, fg_color="transparent")
        btn_frame.pack(pady=10)
        ctk.CTkButton(btn_frame, text="GitHub", width=100, fg_color="#24292e", font=("Ubuntu", 12, "bold"),
                      command=lambda: webbrowser.open("https://github.com/imjanindu")).pack(side="left", padx=5)
        ctk.CTkButton(btn_frame, text="LinkedIn", width=100, fg_color="#0077b5", font=("Ubuntu", 12, "bold"),
                      command=lambda: webbrowser.open("https://linkedin.com/in/imjanindu")).pack(side="left", padx=5)

    def log(self, message):
        self.console.configure(state="normal")
        self.console.insert(ctk.END, f"{message}\n")
        self.console.see(ctk.END)
        self.console.configure(state="disabled")

    def enable_send_mode(self, ip_address, name="Phone"):
        self.last_seen = time.time()
        if self.mobile_ip != ip_address:
            self.mobile_ip = ip_address
            self.send_btn.configure(state="normal", text=f"Send to {name}", fg_color="#1f538d")
            self.status_label.configure(text=f"Connected: {name}", text_color="#4CAF50")
            self.log(f"[+] Connected: {name}")

    def monitor_connection(self):
        while True:
            if self.is_running and self.mobile_ip:
                if time.time() - self.last_seen > 5:
                    self.mobile_ip = None
                    self.send_btn.configure(state="disabled", text="Select File to Send", fg_color="#444444")
                    self.status_label.configure(text="Status: Searching...", text_color="#FF9800")
                    self.log("[-] Lost connection to phone")
            time.sleep(1)

    # --- NETWORKING ---
    def run_presence_listener(self):
        try:
            self.presence_socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            self.presence_socket.bind(('0.0.0.0', PRESENCE_PORT))
            while self.is_running:
                try:
                    data, addr = self.presence_socket.recvfrom(1024)
                    msg = data.decode()
                    if msg.startswith("HELLO_PC"):
                        parts = msg.split("|")
                        phone_name = parts[1] if len(parts) > 1 else addr[0]
                        self.enable_send_mode(addr[0], phone_name)
                        my_ips = self.get_valid_ips()
                        for ip in my_ips:
                            try:
                                s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                                s.sendto(f"BEACON|{ip}|{self.pc_name}".encode(), (addr[0], BROADCAST_PORT))
                                s.close()
                            except: pass
                except: pass
        except Exception as e: self.log(f"[-] Presence Error: {e}")

    def run_discovery_heartbeat(self):
        while self.is_running:
            try:
                my_ips = self.get_valid_ips()
                for ip in my_ips:
                    try:
                        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                        sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
                        sock.bind((ip, 0))
                        msg = f"BEACON|{ip}|{self.pc_name}".encode()
                        sock.sendto(msg, ('255.255.255.255', BROADCAST_PORT))
                        sock.close()
                    except: pass
                time.sleep(1.0)
            except: time.sleep(1)

    def run_tcp_server(self):
        try:
            self.server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            self.server_socket.bind(('0.0.0.0', RECEIVE_PORT))
            self.server_socket.listen(5)
            self.log(f"[+] Listening on Port {RECEIVE_PORT}")
            while self.is_running:
                try:
                    client, addr = self.server_socket.accept()
                    
                    client.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                    client.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, SOCKET_BUFFER)
                    
                    self.enable_send_mode(addr[0], "Phone") 
                    raw = client.recv(1024)
                    if not raw: continue
                    
                    # --- NEW: Safely read batch string from header if it exists ---
                    header = raw.decode('utf-8').rstrip('\x00').split("|")
                    filename, filesize = header[0], int(header[1])
                    
                    batch_str = ""
                    if len(header) >= 4 and header[2].isdigit() and header[3].isdigit():
                        batch_str = f"({header[2]}/{header[3]}) "
                    
                    self.log(f"[*] Receiving {batch_str.strip()}: {filename} ({self.get_size_str(filesize)})")
                    self.cancel_btn.configure(state="normal") 
                    self.toggle_btn.configure(state="disabled") 
                    
                    self.is_transferring = True
                    self.is_cancelled = False
                    
                    save_path = os.path.join(str(Path.home() / "Downloads"), filename)
                    with open(save_path, "wb") as f:
                        rec = 0
                        start = time.time()
                        last_up = 0
                        while rec < filesize:
                            if self.is_cancelled: break 
                            
                            chunk = client.recv(CHUNK_SIZE)
                            if not chunk: break
                            f.write(chunk)
                            rec += len(chunk)
                            now = time.time()
                            if now - last_up > 0.5:
                                last_up = now
                                percent = rec / filesize
                                if (now - start) > 0:
                                    speed = rec / (now - start)
                                    rem = (filesize - rec) / speed
                                    self.progress_label.configure(text=f"Receiving {batch_str.strip()}... {int(rem)}s left")
                                    self.progress_bar.set(percent)
                    
                    self.cancel_btn.configure(state="disabled")
                    self.toggle_btn.configure(state="normal") 
                    self.is_transferring = False 
                    
                    if self.is_cancelled or rec < filesize:
                        self.log(f"[!] Cancelled: {filename}")
                        self.progress_label.configure(text="Cancelled")
                        self.progress_bar.set(0)
                        try: os.remove(save_path) 
                        except: pass
                    else:
                        self.log(f"[+] Saved: {filename}")
                        self.progress_label.configure(text="Transfer Complete")
                        self.progress_bar.set(0)
                        
                    client.close()
                except OSError: 
                    self.cancel_btn.configure(state="disabled")
                    self.toggle_btn.configure(state="normal")
                    self.is_transferring = False
                    break
                except: 
                    self.cancel_btn.configure(state="disabled")
                    self.toggle_btn.configure(state="normal")
                    self.is_transferring = False
                    pass
        except Exception as e:
            if self.is_running: self.log(f"[-] Server Error: {e}")

    def select_files_to_send(self):
        files = ctk.filedialog.askopenfilenames()
        if files: threading.Thread(target=self.send_thread, args=(files,)).start()

    def send_thread(self, files):
        self.cancel_btn.configure(state="normal")
        self.toggle_btn.configure(state="disabled") 
        self.is_cancelled = False
        self.is_transferring = True 
        
        total_files = len(files)
        
        for i, f in enumerate(files):
            if self.is_cancelled: break
            try:
                s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                
                s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                s.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, SOCKET_BUFFER)
                
                s.settimeout(5)
                s.connect((self.mobile_ip, FILE_PORT))
                name = os.path.basename(f)
                size = os.path.getsize(f)
                
                # --- NEW: Batch tracking ---
                batch_str = f"({i+1}/{total_files})"
                self.log(f"[*] Sending {batch_str}: {name} ({self.get_size_str(size)})")
                
                # --- NEW: Append index/total safely to padded header ---
                header = f"{name}|{size}|{i+1}|{total_files}|"
                s.sendall(header.encode().ljust(1024, b'\0'))
                
                with open(f, "rb") as file_data:
                    sent = 0
                    start = time.time()
                    last_up = 0
                    while sent < size:
                        if self.is_cancelled: break 
                        
                        chunk = file_data.read(CHUNK_SIZE)
                        if not chunk: break
                        s.sendall(chunk)
                        sent += len(chunk)
                        
                        now = time.time()
                        if now - last_up > 0.5:
                            last_up = now
                            self.progress_bar.set(sent / size)
                            if (now - start) > 0:
                                speed = sent / (now - start)
                                rem = (size - sent) / speed
                                self.progress_label.configure(text=f"Sending {batch_str}... {int(rem)}s left")
                                
                s.close()
                if self.is_cancelled:
                    self.log(f"[!] Cancelled: {name}")
                    self.progress_label.configure(text="Cancelled")
                else:
                    self.log(f"[+] Sent: {name}")
                    self.progress_label.configure(text="Transfer Complete")
                self.progress_bar.set(0)
            except Exception as e:
                self.log(f"[-] Send Failed: {e}")
                self.progress_label.configure(text="Error")
        
        self.is_transferring = False 
        self.cancel_btn.configure(state="disabled")
        self.toggle_btn.configure(state="normal") 

    def toggle_server(self):
        if not self.is_running:
            self.is_running = True
            self.toggle_btn.configure(text="Stop Service", fg_color="#C62828")
            self.status_label.configure(text="Status: Broadcasting...", text_color="#FF9800")
            threading.Thread(target=self.run_discovery_heartbeat, daemon=True).start()
            threading.Thread(target=self.run_presence_listener, daemon=True).start()
            threading.Thread(target=self.run_tcp_server, daemon=True).start()
        else:
            self.is_running = False
            self.toggle_btn.configure(text="Start Service", fg_color=["#3a7ebf", "#1f538d"])
            self.status_label.configure(text="Status: Service Stopped", text_color="#9E9E9E")
            self.send_btn.configure(state="disabled", text="Select File to Send", fg_color="#444444")
            self.mobile_ip = None
            if self.server_socket:
                try: self.server_socket.close()
                except: pass
            if self.presence_socket:
                try: self.presence_socket.close()
                except: pass
            try:
                my_ips = self.get_valid_ips()
                sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
                for ip in my_ips:
                    try:
                        sock.bind((ip, 0)) 
                        sock.sendto(b"SERVER_STOP", ('255.255.255.255', BROADCAST_PORT))
                    except: pass
                sock.close()
            except: pass
            self.log("[-] Service Stopped.")

if __name__ == "__main__":
    app = EasyShareServer()
    app.mainloop()