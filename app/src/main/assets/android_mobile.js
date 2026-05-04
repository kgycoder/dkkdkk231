/* ════════════════════════════════════════════
   SYNC Android Mobile JS
   - Bottom navigation
   - Media Session API (lockscreen / notification)
   - Mobile UX patches
════════════════════════════════════════════ */

(function () {
    'use strict';

    // ── Inject mobile CSS ──────────────────────
    const cssLink = document.createElement('link');
    cssLink.rel = 'stylesheet';
    cssLink.href = 'android_mobile.css';
    document.head.appendChild(cssLink);

    // ── Bottom Navigation ──────────────────────
    function buildBottomNav() {
        const nav = document.createElement('div');
        nav.id = 'android-bottom-nav';
        nav.innerHTML = `
            <button class="anav-btn on" data-v="home" onclick="androidNav('home',this)">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                    <path d="M3 9l9-7 9 7v11a2 2 0 01-2 2H5a2 2 0 01-2-2z"/>
                    <polyline points="9,22 9,12 15,12 15,22"/>
                </svg>
                홈
            </button>
            <button class="anav-btn" data-v="search" onclick="androidNav('search',this)">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round">
                    <circle cx="11" cy="11" r="8"/>
                    <line x1="21" y1="21" x2="16.65" y2="16.65"/>
                </svg>
                검색
            </button>
            <button class="anav-btn" data-v="fav" onclick="androidNav('fav',this)">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round">
                    <path d="M20.84 4.61a5.5 5.5 0 00-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 00-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 000-7.78z"/>
                </svg>
                즐겨찾기
            </button>
            <button class="anav-btn" data-v="queue" onclick="androidNav('queue',this)">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round">
                    <line x1="8" y1="6" x2="21" y2="6"/>
                    <line x1="8" y1="12" x2="21" y2="12"/>
                    <line x1="8" y1="18" x2="21" y2="18"/>
                    <line x1="3" y1="6" x2="3.01" y2="6"/>
                    <line x1="3" y1="12" x2="3.01" y2="12"/>
                    <line x1="3" y1="18" x2="3.01" y2="18"/>
                </svg>
                대기열
            </button>
            <button class="anav-btn" data-v="playlists" onclick="androidNav('playlists',this)">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                    <rect x="3" y="3" width="7" height="7" rx="1"/>
                    <rect x="14" y="3" width="7" height="7" rx="1"/>
                    <rect x="3" y="14" width="7" height="7" rx="1"/>
                    <line x1="14" y1="17.5" x2="21" y2="17.5"/>
                    <line x1="17.5" y1="14" x2="17.5" y2="21"/>
                </svg>
                플레이리스트
            </button>
        `;
        document.body.appendChild(nav);
    }

    window.androidNav = function (viewId, btn) {
        // Sync with existing gv() function from app.js
        if (typeof gv === 'function') {
            // Find matching desktop nav button
            const desktopBtn = document.querySelector(`.nb[data-v="${viewId}"]`);
            gv(viewId, desktopBtn || btn);
        }
        // Update bottom nav active state
        document.querySelectorAll('.anav-btn').forEach(b => b.classList.remove('on'));
        btn.classList.add('on');
    };

    // Keep bottom nav in sync when desktop nav is clicked
    function syncBottomNav(viewId) {
        document.querySelectorAll('.anav-btn').forEach(b => {
            b.classList.toggle('on', b.dataset.v === viewId);
        });
    }

    // Patch gv to also sync bottom nav
    document.addEventListener('DOMContentLoaded', function () {
        buildBottomNav();

        // Observe nb button clicks to sync bottom nav
        document.querySelectorAll('.nb').forEach(btn => {
            btn.addEventListener('click', function () {
                syncBottomNav(this.dataset.v);
            });
        });

        // ── Swipe-up gesture on mini bar to open NP ──
        setupSwipeGestures();

        // ── Double-tap prevention ──
        preventDoubleZoom();
    });

    // ── Media Session API ──────────────────────
    function updateMediaSession(track, isPlaying) {
        if (!('mediaSession' in navigator)) return;
        if (!track) return;

        navigator.mediaSession.metadata = new MediaMetadata({
            title: track.title || 'SYNC',
            artist: track.channel || '',
            album: 'SYNC',
            artwork: [
                { src: track.thumb || '', sizes: '320x180', type: 'image/jpeg' },
            ]
        });

        navigator.mediaSession.playbackState = isPlaying ? 'playing' : 'paused';

        navigator.mediaSession.setActionHandler('play', function () {
            if (typeof togglePlay === 'function') togglePlay();
        });
        navigator.mediaSession.setActionHandler('pause', function () {
            if (typeof togglePlay === 'function') togglePlay();
        });
        navigator.mediaSession.setActionHandler('nexttrack', function () {
            if (typeof playNext === 'function') playNext();
        });
        navigator.mediaSession.setActionHandler('previoustrack', function () {
            if (typeof playPrev === 'function') playPrev();
        });
        navigator.mediaSession.setActionHandler('seekto', function (details) {
            if (details.seekTime !== undefined && window.S && S.ytPlayer) {
                try { S.ytPlayer.seekTo(details.seekTime, true); } catch (e) {}
            }
        });
    }

    // Hook into app.js state changes
    // We poll S (state object from app.js) for changes
    let _lastTrackId = null, _lastPlaying = null;
    function mediaSessionPoller() {
        if (window.S) {
            const track = S.track;
            const playing = S.playing;
            if (track && (track.id !== _lastTrackId || playing !== _lastPlaying)) {
                _lastTrackId = track.id;
                _lastPlaying = playing;
                updateMediaSession(track, playing);

                // Notify Android native layer for notification bar
                try {
                    if (window.AndroidBridge && window.AndroidBridge.updateNowPlaying) {
                        window.AndroidBridge.updateNowPlaying(
                            track.title || '',
                            track.channel || '',
                            track.thumb || '',
                            playing
                        );
                    }
                } catch (e) {}

                // Update position state
                if ('mediaSession' in navigator && S.dur > 0) {
                    try {
                        navigator.mediaSession.setPositionState({
                            duration: S.dur,
                            playbackRate: 1,
                            position: Math.min(S.cur, S.dur)
                        });
                    } catch (e) {}
                }
            }
        }
        requestAnimationFrame(mediaSessionPoller);
    }
    mediaSessionPoller();

    // ── Swipe Gestures ────────────────────────
    function setupSwipeGestures() {
        const bar = document.getElementById('bar');
        if (!bar) { setTimeout(setupSwipeGestures, 500); return; }

        let startY = 0, startX = 0;
        bar.addEventListener('touchstart', function (e) {
            startY = e.touches[0].clientY;
            startX = e.touches[0].clientX;
        }, { passive: true });

        bar.addEventListener('touchend', function (e) {
            const dy = startY - e.changedTouches[0].clientY;
            const dx = Math.abs(e.changedTouches[0].clientX - startX);
            // Swipe up on mini bar → open NP panel
            if (dy > 50 && dx < 60) {
                const npBtn = document.getElementById('bar-art');
                if (npBtn) npBtn.click();
            }
        }, { passive: true });

        // Swipe down on NP panel → close
        const np = document.getElementById('np');
        if (np) {
            let npStartY = 0;
            np.addEventListener('touchstart', function (e) {
                npStartY = e.touches[0].clientY;
            }, { passive: true });
            np.addEventListener('touchend', function (e) {
                const dy = e.changedTouches[0].clientY - npStartY;
                if (dy > 80) {
                    const closeBtn = document.getElementById('np-close');
                    if (closeBtn) closeBtn.click();
                }
            }, { passive: true });
        }
    }

    // ── Prevent double-tap zoom ───────────────
    function preventDoubleZoom() {
        let lastTap = 0;
        document.addEventListener('touchend', function (e) {
            const now = Date.now();
            if (now - lastTap < 300) {
                e.preventDefault();
            }
            lastTap = now;
        }, { passive: false });
    }

    // ── Notify Android native layer ───────────
    // Called by app.js post() - we intercept overlay calls
    const _originalPostFn = window.post;
    window.post = function (type, extra) {
        if (type === 'overlayMode' || type === 'overlayLyrics') return; // unsupported
        if (type === 'drag' || type === 'minimize' || type === 'maximize' || type === 'close') return;
        if (type === 'setTitle' && window.AndroidBridge) {
            // Relay title to Android for notification
            try { window.AndroidBridge.setTitle(extra && extra.title ? extra.title : 'SYNC'); } catch (e) {}
            return;
        }
        if (_originalPostFn) _originalPostFn(type, extra);
    };

})();
