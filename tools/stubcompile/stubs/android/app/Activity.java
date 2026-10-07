package android.app;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.Window;

/** Stub of android.app.Activity: the lifecycle hooks and the members MainActivity uses. */
public class Activity extends Context {

    public Activity() { }

    protected void onCreate(Bundle savedInstanceState) { }

    protected void onStart() { }

    protected void onRestart() { }

    protected void onResume() { }

    protected void onPause() { }

    protected void onStop() { }

    protected void onDestroy() { }

    protected void onNewIntent(Intent intent) { }

    protected void onSaveInstanceState(Bundle outState) { }

    protected void onRestoreInstanceState(Bundle savedInstanceState) { }

    public void onBackPressed() { }

    public void onWindowFocusChanged(boolean hasFocus) { }

    public void onConfigurationChanged(android.content.res.Configuration newConfig) { }

    public void setContentView(View view) { }

    public void setContentView(int layoutResID) { }

    public Window getWindow() { return new Window(); }

    public Intent getIntent() { return new Intent(); }

    public void setIntent(Intent newIntent) { }

    public void setTitle(CharSequence title) { }

    public void setTitle(int titleId) { }

    public void runOnUiThread(Runnable action) { if (action != null) action.run(); }

    public boolean isFinishing() { return false; }

    public boolean isDestroyed() { return false; }

    public void finish() { }

    public void recreate() { }

    public void setRequestedOrientation(int requestedOrientation) { }

    public int getRequestedOrientation() { return -1; }

    public void setResult(int resultCode) { }

    public void setResult(int resultCode, Intent data) { }

    public View findViewById(int id) { return null; }

    public void openOptionsMenu() { }

    public void closeOptionsMenu() { }

    public void invalidateOptionsMenu() { }
}
