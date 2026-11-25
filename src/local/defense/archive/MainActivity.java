package local.defense.archive;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int PICK_ZIP = 101;
    private static final int BACKGROUND = Color.rgb(20, 22, 28);
    private static final int TITLE = Color.rgb(242, 196, 103);
    private static final String CHOOSE = "Elige defenseborne.zip o pokedefense.zip. La copia se guardará en la aplicación.";

    private final ExecutorService importer = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private File gameFile;
    private GameArchive archive;
    private WebView game;
    private boolean importing;
    private boolean picking;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        gameFile = new File(getFilesDir(), "game.zip");
        picking = state != null && state.getBoolean("picking");
        if (!picking && gameFile.isFile()) {
            openGame();
        } else {
            showImporter(CHOOSE);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean("picking", picking);
    }

    private void showImporter(String message) {
        closeGame();

        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(BACKGROUND);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout container = new LinearLayout(this);
        container.setGravity(Gravity.CENTER);
        container.setPadding(dp(24), dp(24), dp(24), dp(24));
        container.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextColor(TITLE);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setTextSize(27);
        title.setGravity(Gravity.CENTER);
        if (android.os.Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        container.addView(title);

        TextView detail = new TextView(this);
        detail.setText(message);
        detail.setTextSize(16);
        detail.setTextColor(Color.rgb(222, 224, 232));
        detail.setGravity(Gravity.CENTER);
        detail.setPadding(0, dp(14), 0, dp(20));
        detail.setMaxWidth(dp(360));
        detail.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        container.addView(detail);

        if (gameFile.isFile()) {
            Button play = new Button(this);
            play.setText("Abrir juego guardado");
            play.setEnabled(!importing && !picking);
            play.setOnClickListener(view -> openGame());
            container.addView(play);
        }
        Button choose = new Button(this);
        choose.setText("Importar ZIP");
        choose.setEnabled(!importing && !picking);
        choose.setOnClickListener(view -> chooseArchive());
        container.addView(choose);
        scroll.addView(container);
        frame.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        setContentView(frame);
    }

    private void chooseArchive() {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        picker.setType("*/*");
        picking = true;
        try {
            startActivityForResult(picker, PICK_ZIP);
        } catch (android.content.ActivityNotFoundException e) {
            picking = false;
            showImporter("Android no pudo abrir el selector de archivos.");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_ZIP) return;
        picking = false;
        Uri selected = data == null ? null : data.getData();
        if (resultCode != RESULT_OK || selected == null) {
            showImporter(CHOOSE);
            return;
        }
        importing = true;
        showImporter("Importando la copia local…");
        importer.execute(() -> {
            boolean installed = false;
            try (InputStream input = getContentResolver().openInputStream(selected)) {
                if (input == null) throw new IOException("No input stream");
                GameArchive.install(input, gameFile);
                installed = true;
            } catch (IOException | RuntimeException e) {
                android.util.Log.w("DefenseArchive", "ZIP import failed", e);
            }
            boolean ready = installed;
            main.post(() -> {
                if (isDestroyed() || isFinishing()) return;
                importing = false;
                if (ready) openGame();
                else showImporter("No se pudo importar. Comprueba que el ZIP se pueda abrir y elige defenseborne.zip o pokedefense.zip.");
            });
        });
    }

    private void openGame() {
        closeGame();
        try {
            archive = new GameArchive(gameFile);
        } catch (IOException e) {
            showImporter("La copia guardada no se pudo abrir. Importa el ZIP otra vez.");
            return;
        }
        GameArchive current = archive;
        try {
            game = new WebView(this);
        } catch (RuntimeException e) {
            showImporter("No se pudo iniciar Android System WebView. Comprueba que esté disponible y vuelve a intentarlo.");
            return;
        }

        game.setBackgroundColor(BACKGROUND);
        game.getSettings().setJavaScriptEnabled(true);
        game.getSettings().setDomStorageEnabled(true);
        game.getSettings().setAllowFileAccess(false);
        game.getSettings().setAllowContentAccess(false);
        game.getSettings().setBlockNetworkLoads(true);
        game.getSettings().setMediaPlaybackRequiresUserGesture(true);
        game.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage message) {
                if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    android.util.Log.w("DefenseArchive",
                        message.sourceId() + ":" + message.lineNumber() + " " + message.message());
                }
                return true;
            }
        });
        game.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return current.respond(request.getUrl(), request.getMethod());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (GameArchive.isLocal(uri)) return false;
                if (request.isForMainFrame() && request.hasGesture() && "https".equals(uri.getScheme())) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE));
                    } catch (android.content.ActivityNotFoundException ignored) {
                        // The offline game can proceed without an external browser.
                    }
                }
                return true;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (view == game && request.isForMainFrame()) {
                    showImporter("No se pudo cargar el juego. Puedes volver a abrirlo o importar el ZIP otra vez.");
                }
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                if (view == game) showImporter("El visor del juego se cerró. Puedes abrirlo otra vez.");
                return true;
            }
        });
        setContentView(game);
        game.loadUrl(GameArchive.START);
    }

    private void closeGame() {
        if (game != null) {
            if (game.getParent() instanceof ViewGroup) ((ViewGroup) game.getParent()).removeView(game);
            game.destroy();
            game = null;
        }
        if (archive != null) {
            try {
                archive.close();
            } catch (IOException ignored) {
                // The next launch opens a separate archive handle.
            }
            archive = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (importing) return;
        if (game == null) super.onBackPressed();
        else if (game.canGoBack()) game.goBack();
        else moveTaskToBack(true);
    }

    @Override
    protected void onPause() {
        if (game != null) game.onPause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (game != null) game.onResume();
    }

    @Override
    protected void onDestroy() {
        importer.shutdown();
        closeGame();
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
