package springs.sacco.remoteprint;



import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import com.android.volley.AuthFailureError;
import com.android.volley.DefaultRetryPolicy;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.toolbox.StringRequest;
import com.android.volley.toolbox.Volley;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

public class PrintService extends Service {

    RequestQueue requestQueue;
    Handler handler = new Handler();

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d("Printer", "Service onCreate - Initializing Printer...");
        requestQueue = Volley.newRequestQueue(this);
    }


    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 1. Create a notification (Required for Foreground Services in Android 13)
        createNotificationChannel();
        Notification notification = new NotificationCompat.Builder(this, "PRINT_CHANNEL")
                .setContentTitle("Springs PDQ Printer Active")
                .setContentText("Continuously Retrieving Receipts for Printing")
                .setSmallIcon(R.drawable.reports_prints)
                .build();

        // 2. Make this service "immortal"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(1, notification);
        }



        // 3. Start your Firestore listener here
        initPrintingListener();

        return START_STICKY;
    }

    private void initPrintingListener() {
        Log.d("Printer", "Starting Heartbeat Listener...");

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                Log.d("Printer", "Heartbeat: Checking for Receipts...");

                // Start a new background thread so the UI doesn't freeze
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        // This now runs in the background
                        fetchJobsFromServer();
                    }
                }).start();

                // Schedule the next check in 5 seconds
                handler.postDelayed(this, 10000);
            }
        }, 5000);
    }

    private void fetchJobsFromServer() {
        String url = "https://dltsacco.co.ke/DigitalSacco/get_print_jobs.php";



        StringRequest stringRequest = new StringRequest(Request.Method.GET, url,
                response -> {
                    new Thread(() -> {
                        try {

                            JSONArray array = new JSONArray(response);
                            if (array.length() == 0) return;

                            for (int i = 0; i < array.length(); i++) {
                                JSONObject obj = array.getJSONObject(i);
                                String jobId = obj.getString("id");
                                String reportData = obj.getString("report_data");

                                // No more "Waiting for hardware handshake" loop!
                                updateStatus(jobId, "queued");
                                String header = "<110>SPRINGS SACCO\n";
                                handler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        triggerPrintIntent(header + reportData, jobId);
                                    }
                                });

                                updateStatus(jobId, "printed");

                                try {
                                    // Increase to 8 seconds if 5 wasn't working.
                                    // Thermal printing is mechanical and slow.
                                    Thread.sleep(8000);
                                    Log.d("Printer", "Waiting to reconnect to Thermal");

                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                            }
                        } catch (Exception e) {
                            Log.e("Printer", "Loop Error", e);
                        }
                    }).start();
                }, error -> {
            Log.e("Printer", "Poll Failed: " + error.getMessage());
        }
        ){
            @Override
            public Map<String, String> getHeaders() throws AuthFailureError {
                Map<String, String> params = new HashMap<>();
                // This tells the server the request is coming from a real browser
                params.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
                params.put("Accept", "application/json");
                params.put("Connection", "keep-alive");
                return params;
            }
        };



        // Use the global requestQueue we discussed in the previous step
        requestQueue.add(stringRequest);


    }

    private void triggerPrintIntent(String s, String jobId) {

        String formattedData = "<000>" + s;

        Intent sendIntent = getSendIntent(formattedData);

        try {
            startActivity(sendIntent);
        } catch (Exception e) {
            Log.e("Printer", "Bridge app missing", e);
            // If the app is missing, we revert the job to pending
            updateStatus(jobId, "pending");
            showFailureNotification();
        }
    }

    private @NonNull Intent getSendIntent(String formattedData) {
        Intent sendIntent = new Intent();
        sendIntent.setAction(Intent.ACTION_SEND); //
        sendIntent.setPackage("mate.bluetoothprint"); //
        sendIntent.putExtra(Intent.EXTRA_TEXT, formattedData); //
        sendIntent.setType("text/plain"); //

        sendIntent.addFlags(Intent.FLAG_FROM_BACKGROUND);

        // Critical: Ensure the intent starts fresh and from a background service

        sendIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        sendIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
      //  sendIntent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK); // Forces a fresh instance


        return sendIntent;
    }

    private void showFailureNotification() {
        new Handler(Looper.getMainLooper()).post(() -> {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            String channelId = "print_errors";

            NotificationChannel channel = new NotificationChannel(channelId, "Print Errors", NotificationManager.IMPORTANCE_HIGH);
            nm.createNotificationChannel(channel);

            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, channelId)
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentTitle("Printer Error")
                    .setContentText("Printing app missing. Please reinstall 'Thermal' app.")
                    .setPriority(NotificationCompat.PRIORITY_HIGH);

            nm.notify(1, builder.build());
        });

    }

    // Helper to send status updates to PHP
    private void updateStatus(String id, String status) {
        String url = "https://dltsacco.co.ke/DigitalSacco/update_status.php?id=" + id + "&status=" + status;

        StringRequest req = new StringRequest(Request.Method.GET, url,
                response -> {
                    try {
                        JSONObject jsonObject = new JSONObject(response);
                        if (jsonObject.has("success") && jsonObject.getBoolean("success")) {
                            Log.d("Update", "Job " + id + " successfully marked as " + status);
                        } else {
                            // Server returned {"error": "Invalid Data"}
                            Log.e("Update", "Server rejected data. Retrying in 5s...");
                            retryUpdate(id, status);
                        }
                    } catch (JSONException e) {
                        Log.e("Update", "Malformed JSON. Retrying in 5s...");
                        retryUpdate(id, status);
                    }
                },
                error -> {
                    // Detailed error logging we discussed
                    String message = error.getMessage();
                    if (error.networkResponse != null) {
                        message = "Status Code: " + error.networkResponse.statusCode;
                    }
                    Log.e("Update", "Network error: " + message + ". Retrying in 5s...");
                    retryUpdate(id, status);
                });

        // 1. Add the 20-second timeout policy here too!
        req.setRetryPolicy(new DefaultRetryPolicy(
                20000,
                DefaultRetryPolicy.DEFAULT_MAX_RETRIES,
                DefaultRetryPolicy.DEFAULT_BACKOFF_MULT));

        // 2. Use the global queue instead of creating a new one
        if (requestQueue != null) {
            requestQueue.add(req);
        }
    }

    // This helper ensures we don't stop until the server says "Success"
    private void retryUpdate(String id, String status) {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            updateStatus(id, status);
        }, 2000); // Wait 2 seconds before trying again
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel("PRINT_CHANNEL", "Printer Service", NotificationManager.IMPORTANCE_LOW);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}