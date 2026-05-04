/* ════════════════════════════════════════════
   SYNC Android Bridge
   Replaces window.chrome.webview.postMessage
   with Android.postMessage() native interface
════════════════════════════════════════════ */

// Polyfill chrome.webview for Android
(function () {
    'use strict';

    // Android JavascriptInterface is injected as window.Android
    // We shim window.chrome.webview to route through it
    if (!window.chrome) window.chrome = {};
    window.chrome.webview = {
        postMessage: function (json) {
            try {
                if (window.AndroidBridge) {
                    window.AndroidBridge.postMessage(json);
                }
            } catch (e) {
                console.error('[Bridge] postMessage error:', e);
            }
        }
    };

    // Android calls this to deliver responses back to JS
    window.__androidCallback = function (json) {
        if (window.__sync) {
            window.__sync(json);
        }
    };

    // ── Android-specific UI patches ──────────────
    // Hide Windows title bar buttons (close/min/max)
    document.addEventListener('DOMContentLoaded', function () {
        const tbar = document.getElementById('tbar');
        if (tbar) tbar.style.display = 'none';

        // Add Android status bar spacer
        const spacer = document.createElement('div');
        spacer.id = 'android-status-bar-spacer';
        spacer.style.cssText = 'height:env(safe-area-inset-top,24px);width:100%;flex-shrink:0;';
        document.body.insertBefore(spacer, document.body.firstChild);

        // Patch drag (no-op on Android)
        window._originalPost = window.post;

        // Patch overlay mode calls (unsupported on Android)
        const _origSync = window.__sync;
        // Already handled - overlay just ignored

        // Fix shell layout for mobile
        const shell = document.getElementById('shell');
        if (shell) {
            shell.style.paddingTop = '0';
        }

        // Adjust sidebar for mobile bottom nav
        patchMobileLayout();
    });

    function patchMobileLayout() {
        // Wait for full DOM
        setTimeout(() => {
            const side = document.getElementById('side');
            const main = document.getElementById('main');
            const bar = document.getElementById('bar');

            if (side) {
                side.setAttribute('data-android', 'true');
            }
        }, 100);
    }
})();
