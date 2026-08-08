package online.monarchlabs.sentinel.utils;

import android.app.Dialog;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;
import androidx.annotation.NonNull;
import online.monarchlabs.sentinel.R;

/**
 * LoadingDialogManager - A utility class to manage loading dialogs during authentication flows
 * 
 * Features:
 * - Shows a non-cancelable loading dialog with custom text
 * - Prevents user from going back or interacting with other UI elements
 * - Smooth animations and modern design
 * - Customizable loading messages
 */
public class LoadingDialogManager {
    
    private Dialog loadingDialog;
    private TextView tvLoadingText;
    private TextView tvLoadingSubtext;
    private Context context;
    
    public LoadingDialogManager(@NonNull Context context) {
        this.context = context;
        initializeDialog();
    }
    
    private void initializeDialog() {
        if (loadingDialog != null) {
            return;
        }
        
        // Create the dialog
    loadingDialog = new Dialog(context, android.R.style.Theme_DeviceDefault_Light_NoActionBar_Fullscreen);

    // Inflate the loading layout
    View loadingView = LayoutInflater.from(context).inflate(R.layout.dialog_loading, null);
    loadingDialog.setContentView(loadingView);
        
        // Get references to text views
        tvLoadingText = loadingView.findViewById(R.id.tvLoadingText);
        tvLoadingSubtext = loadingView.findViewById(R.id.tvLoadingSubtext);
        
        // Configure dialog properties
        Window window = loadingDialog.getWindow();
        if (window != null) {
            // Make dialog full screen and dim the background for focus
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            WindowManager.LayoutParams params = window.getAttributes();
            params.dimAmount = 0.6f; // dim background
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setAttributes(params);
        }
        
        // Make dialog non-cancelable to prevent user from dismissing it
        loadingDialog.setCancelable(false);
        loadingDialog.setCanceledOnTouchOutside(false);
    }
    
    /**
     * Show the loading dialog with default text
     */
    public void show() {
        show("Verifying OTP...", "Please wait while we authenticate you");
    }
    
    /**
     * Show the loading dialog with custom text
     * @param title Main loading text
     * @param subtitle Subtitle text
     */
    public void show(String title, String subtitle) {
        if (loadingDialog == null) {
            initializeDialog();
        }
        
        if (tvLoadingText != null) {
            tvLoadingText.setText(title);
        }
        
        if (tvLoadingSubtext != null) {
            tvLoadingSubtext.setText(subtitle);
        }
        
        if (!loadingDialog.isShowing()) {
            loadingDialog.show();
        }
    }
    
    /**
     * Update the loading text without dismissing the dialog
     * @param title Main loading text
     * @param subtitle Subtitle text
     */
    public void updateText(String title, String subtitle) {
        if (tvLoadingText != null) {
            tvLoadingText.setText(title);
        }
        
        if (tvLoadingSubtext != null) {
            tvLoadingSubtext.setText(subtitle);
        }
    }
    
    /**
     * Update only the main loading text
     * @param title Main loading text
     */
    public void updateText(String title) {
        if (tvLoadingText != null) {
            tvLoadingText.setText(title);
        }
    }
    
    /**
     * Hide the loading dialog
     */
    public void hide() {
        if (loadingDialog != null && loadingDialog.isShowing()) {
            loadingDialog.dismiss();
        }
    }
    
    /**
     * Check if the loading dialog is currently showing
     * @return true if dialog is showing
     */
    public boolean isShowing() {
        return loadingDialog != null && loadingDialog.isShowing();
    }
    
    /**
     * Clean up resources when the activity is destroyed
     */
    public void cleanup() {
        if (loadingDialog != null && loadingDialog.isShowing()) {
            loadingDialog.dismiss();
        }
        loadingDialog = null;
        tvLoadingText = null;
        tvLoadingSubtext = null;
    }
}
