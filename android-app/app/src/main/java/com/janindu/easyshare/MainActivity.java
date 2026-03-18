package com.janindu.easyshare;

import android.Manifest;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.app.AlertDialog;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends AppCompatActivity {

    private TextView tvStatus, tvConsole, tvProgress, tvDeviceName;
    private Button btnSelectFile, btnCancel;
    private ImageButton btnAbout, btnEditName;
    private ProgressBar progressBar;

    private String pcIp = null;
    private String pcName = "Unknown PC";
    private String myDeviceName;
    private long lastHeartbeatTime = 0;
    private boolean isAppRunning = true;

    private ArrayList<Uri> pendingFiles = new ArrayList<>();

    private static final AtomicBoolean isListening = new AtomicBoolean(false);
    private static final AtomicBoolean isReceiving = new AtomicBoolean(false);
    private static final AtomicBoolean isShouting = new AtomicBoolean(false);
    private static final AtomicBoolean isCancelled = new AtomicBoolean(false);

    private final ExecutorService networkExecutor = Executors.newCachedThreadPool();
    private SharedPreferences prefs;

    // --- SPEED CONFIGURATION ---
    private static final int CHUNK_SIZE = 1024 * 1024; // 1MB Array
    private static final int OS_BUFFER = 4 * 1024 * 1024; // 4MB RAM Buffer

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tvStatus);
        tvConsole = findViewById(R.id.tvConsole);
        tvProgress = findViewById(R.id.tvProgress);
        tvDeviceName = findViewById(R.id.tvDeviceName);
        progressBar = findViewById(R.id.progressBar);
        btnSelectFile = findViewById(R.id.btnSelectFile);
        btnCancel = findViewById(R.id.btnCancel);
        btnAbout = findViewById(R.id.btnAbout);
        btnEditName = findViewById(R.id.btnEditName);

        prefs = getSharedPreferences("EasySharePrefs", MODE_PRIVATE);
        myDeviceName = prefs.getString("device_name", Build.MODEL);
        tvDeviceName.setText(myDeviceName);

        btnSelectFile.setEnabled(false);
        btnSelectFile.setAlpha(0.5f);
        btnCancel.setEnabled(false);
        btnCancel.setAlpha(0.5f);

        btnSelectFile.setOnClickListener(v -> {
            if (pcIp != null) openFilePicker();
        });

        btnCancel.setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle("Stop Transfer")
                    .setMessage("Are you sure you want to cancel the transfer?")
                    .setPositiveButton("Yes", (dialog, which) -> {
                        isCancelled.set(true);
                        logToConsole("[!] Cancelling...");
                        btnCancel.setEnabled(false);
                    })
                    .setNegativeButton("No", null)
                    .show();
        });

        tvStatus.setOnClickListener(v -> showManualIpDialog());
        btnAbout.setOnClickListener(v -> showAboutDialog());
        btnEditName.setOnClickListener(v -> showRenameDialog());

        checkPermissions();
        startStatusChecker();

        handleIncomingIntent(getIntent());
    }

    private String getSizeStr(long size) {
        if (size < 1024) return size + " B";
        double z = size / 1024.0;
        if (z < 1024) return String.format("%.1f KB", z);
        z /= 1024.0;
        if (z < 1024) return String.format("%.1f MB", z);
        z /= 1024.0;
        return String.format("%.1f GB", z);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    private void handleIncomingIntent(Intent intent) {
        String action = intent.getAction();
        String type = intent.getType();

        if (Intent.ACTION_SEND.equals(action) && type != null) {
            Uri imageUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (imageUri != null) {
                pendingFiles.add(imageUri);
                logToConsole("[*] File queued. Waiting for connection...");
                Toast.makeText(this, "File queued!", Toast.LENGTH_SHORT).show();
            }
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action) && type != null) {
            ArrayList<Uri> imageUris = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (imageUris != null) {
                pendingFiles.addAll(imageUris);
                logToConsole("[*] " + imageUris.size() + " files queued.");
                Toast.makeText(this, "Files queued!", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void sendFilesSequentially(List<Uri> files) {
        if (files == null || files.isEmpty() || pcIp == null) return;

        runOnUiThread(() -> {
            btnCancel.setEnabled(true);
            btnCancel.setAlpha(1.0f);
        });
        isCancelled.set(false);

        networkExecutor.execute(() -> {
            for (int i = 0; i < files.size(); i++) {
                if (isCancelled.get()) break;
                Uri uri = files.get(i);
                sendFileToWindowsBlocking(uri, i + 1, files.size());
            }
            runOnUiThread(() -> {
                if (isCancelled.get()) {
                    logToConsole("[!] Transfer Cancelled");
                    tvProgress.setText("Cancelled");
                } else {
                    logToConsole("[+] All files sent.");
                    tvProgress.setText("Transfer Complete");
                }
                progressBar.setProgress(0);
                btnCancel.setEnabled(false);
                btnCancel.setAlpha(0.5f);
            });
        });
    }

    private void sendFileToWindowsBlocking(Uri fileUri, int current, int total) {
        try {
            String filename = getFileName(fileUri);
            long filesize = getFileSize(fileUri);

            runOnUiThread(() -> {
                logToConsole("[*] Sending (" + current + "/" + total + "): " + filename + " (" + getSizeStr(filesize) + ")");
                tvProgress.setText("Sending (" + current + "/" + total + "): " + filename);
                progressBar.setProgress(0);
            });

            Socket socket = new Socket(pcIp, 5001);

            socket.setTcpNoDelay(true);
            socket.setSendBufferSize(OS_BUFFER);

            OutputStream out = new java.io.BufferedOutputStream(socket.getOutputStream(), OS_BUFFER);
            InputStream in = new java.io.BufferedInputStream(getContentResolver().openInputStream(fileUri), OS_BUFFER);

            // --- NEW: Append index/total safely to padded header ---
            String header = filename + "|" + filesize + "|" + current + "|" + total + "|";
            byte[] headerBytes = header.getBytes(StandardCharsets.UTF_8);
            byte[] paddedHeader = new byte[1024];
            System.arraycopy(headerBytes, 0, paddedHeader, 0, headerBytes.length);
            out.write(paddedHeader);
            out.flush();

            byte[] buffer = new byte[CHUNK_SIZE];
            long totalSent = 0;
            int read;
            long startTime = System.currentTimeMillis();
            long lastUpdate = 0;

            while ((read = in.read(buffer)) != -1) {
                if (isCancelled.get()) break;

                out.write(buffer, 0, read);
                totalSent += read;

                long now = System.currentTimeMillis();
                if (now - lastUpdate > 500) {
                    lastUpdate = now;
                    int percent = (int) ((totalSent * 100) / filesize);
                    long elapsed = now - startTime;

                    if (elapsed > 0) {
                        long speed = totalSent / elapsed;
                        if (speed > 0) {
                            long remaining = filesize - totalSent;
                            long remainingMs = remaining / speed;
                            final String timeText = (remainingMs / 1000) + "s left";

                            runOnUiThread(() -> {
                                progressBar.setProgress(percent);
                                tvProgress.setText("Sending (" + current + "/" + total + "): " + timeText);
                            });
                        }
                    }
                }
            }
            out.flush();
            socket.close();
            in.close();

        } catch (Exception e) {
            runOnUiThread(() -> logToConsole("[-] Send Failed: " + e.getMessage()));
        }
    }

    private void startStatusChecker() {
        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (!isAppRunning) return;
                long now = System.currentTimeMillis();
                if (pcIp != null && (now - lastHeartbeatTime < 8000)) {
                    tvStatus.setText("Connected to:\n" + pcName);
                    tvStatus.setTextColor(android.graphics.Color.GREEN);
                    btnSelectFile.setEnabled(true);
                    btnSelectFile.setAlpha(1.0f);

                    if (!pendingFiles.isEmpty()) {
                        logToConsole("[*] Auto-sending " + pendingFiles.size() + " queued files...");
                        ArrayList<Uri> filesToSend = new ArrayList<>(pendingFiles);
                        pendingFiles.clear();
                        sendFilesSequentially(filesToSend);
                    }
                } else {
                    tvStatus.setText("Searching...");
                    tvStatus.setTextColor(android.graphics.Color.RED);
                    btnSelectFile.setEnabled(false);
                    btnSelectFile.setAlpha(0.5f);
                    if (now - lastHeartbeatTime > 10000) pcIp = null;
                }
                handler.postDelayed(this, 1000);
            }
        });
    }

    private final ActivityResultLauncher<Intent> launcher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), r -> {
                if (r.getResultCode() == RESULT_OK && r.getData() != null) {
                    Intent d = r.getData();
                    ArrayList<Uri> selectedFiles = new ArrayList<>();
                    if (d.getClipData() != null) {
                        int count = d.getClipData().getItemCount();
                        for (int i = 0; i < count; i++) {
                            selectedFiles.add(d.getClipData().getItemAt(i).getUri());
                        }
                    } else if (d.getData() != null) {
                        selectedFiles.add(d.getData());
                    }
                    sendFilesSequentially(selectedFiles);
                }
            });

    private void showRenameDialog() {
        EditText input = new EditText(this);
        input.setText(myDeviceName);
        new AlertDialog.Builder(this).setTitle("Rename Device").setView(input).setPositiveButton("Save", (d, w) -> {
            String newName = input.getText().toString().trim();
            if (!newName.isEmpty()) { myDeviceName = newName; tvDeviceName.setText(myDeviceName); prefs.edit().putString("device_name", myDeviceName).apply(); }
        }).setNegativeButton("Cancel", null).show();
    }

    private void showAboutDialog() {
        new AlertDialog.Builder(this).setTitle("About EasyShare").setMessage("Developer: Janindu Malshan\nVersion: 1.0\n\nGitHub: github.com/imjanindu\nLinkedIn: linkedin.com/in/imjanindu").setPositiveButton("Close", null).show();
    }

    private void showManualIpDialog() {
        final EditText input = new EditText(this); input.setInputType(android.text.InputType.TYPE_CLASS_PHONE);
        new AlertDialog.Builder(this).setTitle("Manual Connection").setView(input).setPositiveButton("Connect", (d, w) -> {
            pcIp = input.getText().toString().trim(); sendHelloToWindows(pcIp);
        }).show();
    }

    private void openFilePicker() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("*/*"); i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true); launcher.launch(i);
    }

    private void logToConsole(String msg) { runOnUiThread(() -> tvConsole.append("\n" + msg)); }

    private String getFileName(Uri uri) {
        String result = null; if (uri.getScheme().equals("content")) { try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) { if (cursor != null && cursor.moveToFirst()) result = cursor.getString(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)); } catch(Exception e){} }
        if (result == null) { result = uri.getPath(); int cut = result.lastIndexOf('/'); if (cut != -1) result = result.substring(cut + 1); } return result;
    }

    private long getFileSize(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) { if (cursor != null && cursor.moveToFirst()) return cursor.getLong(cursor.getColumnIndex(OpenableColumns.SIZE)); } catch(Exception e){} return 0;
    }

    private void checkPermissions() {
        String[] p; if (Build.VERSION.SDK_INT >= 33) p = new String[]{Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.ACCESS_FINE_LOCATION}; else p = new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE};
        if (ActivityCompat.checkSelfPermission(this, p[0]) != PackageManager.PERMISSION_GRANTED) ActivityCompat.requestPermissions(this, p, 101); else startServices();
    }

    @Override public void onRequestPermissionsResult(int r, @NonNull String[] p, @NonNull int[] g) { super.onRequestPermissionsResult(r, p, g); if (r == 101 && g.length > 0 && g[0] == PackageManager.PERMISSION_GRANTED) startServices(); }

    private void startServices() { if (!isListening.get()) networkExecutor.execute(this::runHeartbeatListener); if (!isReceiving.get()) networkExecutor.execute(this::runFileReceiver); if (!isShouting.get()) networkExecutor.execute(this::runActiveDiscovery); }

    private void runActiveDiscovery() { if (isShouting.get()) return; isShouting.set(true); try (DatagramSocket udp = new DatagramSocket()) { udp.setBroadcast(true); InetAddress b = InetAddress.getByName("255.255.255.255"); while (isAppRunning) { byte[] m = ("HELLO_PC|" + myDeviceName).getBytes(); udp.send(new DatagramPacket(m, m.length, pcIp==null?b:InetAddress.getByName(pcIp), 5006)); Thread.sleep(2000); } } catch(Exception e){} isShouting.set(false); }

    private void runHeartbeatListener() {
        if (isListening.get()) return;
        isListening.set(true);
        try {
            DatagramSocket s = new DatagramSocket(null); s.setReuseAddress(true); s.bind(new InetSocketAddress(5005));
            byte[] b = new byte[1024];
            DatagramPacket p = new DatagramPacket(b, b.length);
            while (isAppRunning) {
                s.receive(p);
                String m = new String(p.getData(), 0, p.getLength());
                if (m.equals("SERVER_STOP")) { pcIp = null; runOnUiThread(() -> { tvStatus.setText("Searching..."); tvStatus.setTextColor(android.graphics.Color.RED); btnSelectFile.setEnabled(false); btnSelectFile.setAlpha(0.5f); }); continue; }
                if (m.startsWith("BEACON|")) { String[] parts = m.split("\\|"); if (parts.length >= 2) { pcIp = parts[1]; if (parts.length >= 3) pcName = parts[2]; lastHeartbeatTime = System.currentTimeMillis(); sendHelloToWindows(pcIp); } }
            }
        } catch(Exception e){}
        isListening.set(false);
    }

    private void runFileReceiver() {
        if (isReceiving.get()) return;
        isReceiving.set(true);
        try (ServerSocket server = new ServerSocket(6000)) {
            runOnUiThread(() -> logToConsole("[+] Receiver Started"));
            while (isAppRunning) {
                try {
                    Socket client = server.accept();

                    client.setTcpNoDelay(true);
                    client.setReceiveBufferSize(OS_BUFFER);

                    InputStream in = client.getInputStream();
                    byte[] headerBuf = new byte[1024];
                    int r = in.read(headerBuf);
                    if (r != -1) {
                        String header = new String(headerBuf, StandardCharsets.UTF_8).trim();
                        String[] parts = header.split("\\|");
                        if (parts.length >= 2) {
                            String filename = parts[0];
                            long size = Long.parseLong(parts[1]);

                            // --- NEW: Safely read batch string from header if it exists ---
                            String batchInfo = "";
                            if (parts.length >= 4 && !parts[2].isEmpty() && !parts[3].isEmpty()) {
                                batchInfo = " (" + parts[2] + "/" + parts[3] + ")";
                            }
                            final String finalBatchInfo = batchInfo;

                            runOnUiThread(() -> {
                                tvProgress.setText("Receiving" + finalBatchInfo + ": " + filename);
                                progressBar.setProgress(0);
                                btnCancel.setEnabled(true);
                                btnCancel.setAlpha(1.0f);
                            });
                            isCancelled.set(false);

                            saveFileToDownloads(in, filename, size, finalBatchInfo);

                            runOnUiThread(() -> {
                                btnCancel.setEnabled(false);
                                btnCancel.setAlpha(0.5f);
                            });
                        }
                    }
                    client.close();
                } catch (Exception e) { }
            }
        } catch (IOException e) { logToConsole("[-] Receiver Failed: " + e.getMessage()); }
        finally { isReceiving.set(false); }
    }

    // Pass batchInfo down to update logs and status safely
    private void saveFileToDownloads(InputStream in, String filename, long size, String batchInfo) throws IOException {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, filename);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "*/*");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);

        Uri uri = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        }

        if (uri == null) return;

        OutputStream fos = new java.io.BufferedOutputStream(getContentResolver().openOutputStream(uri), OS_BUFFER);

        byte[] buf = new byte[CHUNK_SIZE];
        long total = 0;
        int len;
        long startTime = System.currentTimeMillis();
        long lastUpdate = 0;

        runOnUiThread(() -> logToConsole("[*] Receiving" + batchInfo + ": " + filename + " (" + getSizeStr(size) + ")"));

        try {
            while (total < size && (len = in.read(buf)) != -1) {
                if (isCancelled.get()) break;

                fos.write(buf, 0, len);
                total += len;

                long now = System.currentTimeMillis();
                if (now - lastUpdate > 500) {
                    lastUpdate = now;
                    int percent = (int) ((total * 100) / size);
                    long elapsed = now - startTime;

                    if (elapsed > 0) {
                        long speed = total / elapsed;
                        if (speed > 0) {
                            long remainingBytes = size - total;
                            long remainingMs = remainingBytes / speed;
                            final String timeText = (remainingMs / 1000) + "s left";

                            runOnUiThread(() -> {
                                progressBar.setProgress(percent);
                                tvProgress.setText("Receiving" + batchInfo + ": " + timeText);
                            });
                        }
                    }
                }
            }
        } catch (IOException e) {
            // Connection interrupted by sender.
        } finally {
            try { fos.close(); } catch (Exception e) {}
        }

        if (isCancelled.get() || total < size) {
            try { getContentResolver().delete(uri, null, null); } catch (Exception e) {}
            runOnUiThread(() -> {
                logToConsole("[!] Cancelled: " + filename);
                tvProgress.setText("Cancelled");
                progressBar.setProgress(0);
            });
        } else {
            runOnUiThread(() -> {
                logToConsole("[+] Saved: " + filename);
                tvProgress.setText("Transfer Complete");
                progressBar.setProgress(0);
                Toast.makeText(MainActivity.this, "File Received!", Toast.LENGTH_SHORT).show();
            });
        }
    }

    private void sendHelloToWindows(String ip) { networkExecutor.execute(() -> { try { DatagramSocket u = new DatagramSocket(); byte[] d = ("HELLO_PC|" + myDeviceName).getBytes(); u.send(new DatagramPacket(d, d.length, InetAddress.getByName(ip), 5006)); u.close(); } catch(Exception e){} }); }
}