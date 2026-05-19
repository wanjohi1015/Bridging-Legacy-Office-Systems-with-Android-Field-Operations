package springs.sacco.remoteprint;


// Add these imports
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkInfo;
import android.net.http.SslError;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {



    WebView webView;
    LinearLayout layoutNoNet,ssl;
    Button btnRetry, kill;
    String url = "https://xxxxxxxxx/xxxxxxx/index.php?device=pdq_001";
    @SuppressLint("SetJavaScriptEnabled")
    Handler handler = new Handler();

    String  lastUrl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);



        // 2. Setup the Browser
        webView = findViewById(R.id.saccoWebView);
        layoutNoNet = findViewById(R.id.layoutNoNet);
        ssl = findViewById(R.id.ssl);
        btnRetry = findViewById(R.id.btnRetry);
        kill  = findViewById(R.id.kill);



        handler.post(this::setupWebView);

        new Handler(Looper.getMainLooper()).post(() -> {

        ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);

        connectivityManager.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
            @Override
            public void onLost(@NonNull Network network) {
                super.onLost(network);
                runOnUiThread(() -> {
                    lastUrl = webView.getUrl(); // Bookmark current location
                    showError();
                });
            }

            @Override
            public void onAvailable(@NonNull Network network) {
                super.onAvailable(network);
                new Handler(Looper.getMainLooper()).post(() -> {
                String urlToLoad = (lastUrl != null && !lastUrl.isEmpty()) ? lastUrl : url;
                webView.loadUrl(urlToLoad);
                layoutNoNet.setVisibility(View.GONE);
                webView.setVisibility(View.VISIBLE);
                });
            }
        });

        });

        btnRetry.setOnClickListener(v -> {
            if (isNetworkAvailable()) {
                // Only switch back to WebView if internet is actually back
                new Handler(Looper.getMainLooper()).post(() -> {
                    String urlToLoad = (lastUrl != null && !lastUrl.isEmpty()) ? lastUrl : url;
                    webView.loadUrl(urlToLoad);
                    layoutNoNet.setVisibility(View.GONE);
                    webView.setVisibility(View.VISIBLE);
                });
            } else {
                // Internet is still down, stay on this screen
                // Optionally show a quick message so the user knows the click worked
                Toast.makeText(MainActivity.this, "Still no connection. Try again.", Toast.LENGTH_SHORT).show();

                // Keep the error layout visible and the WebView hidden
                layoutNoNet.setVisibility(View.VISIBLE);
                webView.setVisibility(View.GONE);
            }
        });

        kill.setOnClickListener(v -> {
            startActivity(new Intent(android.provider.Settings.ACTION_DATE_SETTINGS));
            new Handler().postDelayed(() -> {
                finishAffinity(); // Closes all activities in the stack
                System.exit(0);
            }, 3000);
                });

        loadPortal();
        // 1. Start your PrintService (This maintains your fetchJobsFromServer logic)
        Intent serviceIntent = new Intent(this, PrintService.class);
        startForegroundService(serviceIntent);
    }

    private void setupWebView() {
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setLoadWithOverviewMode(true);
        webSettings.setUseWideViewPort(true);
        webSettings.setCacheMode(WebSettings.LOAD_DEFAULT);
        webSettings.setRenderPriority(WebSettings.RenderPriority.HIGH); // Gives the browser more "weight"

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                // Optional: Show a loading spinner here
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                // ONLY show the WebView once we are sure the page content is there
                layoutNoNet.setVisibility(View.GONE);
                webView.setVisibility(View.VISIBLE);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    showError();
                }
            }
            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel();
                webView.setVisibility(View.GONE);
                layoutNoNet.setVisibility(View.GONE);
                ssl.setVisibility(View.VISIBLE);
            }
        });
    }

    private void loadPortal() {
        new Handler(Looper.getMainLooper()).post(() -> {
        if (isNetworkAvailable()) {
            webView.loadUrl(url);
        } else {
            showError();
        }
        });
    }

    private void showError() {
        webView.setVisibility(View.GONE);
        layoutNoNet.setVisibility(View.VISIBLE);
    }

    private boolean isNetworkAvailable() {

        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();

        return activeNetwork != null && activeNetwork.isConnectedOrConnecting();

    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
