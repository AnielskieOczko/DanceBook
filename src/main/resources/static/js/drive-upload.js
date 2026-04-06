/**
 * Google Drive Direct Upload Module (OAuth version)
 * 
 * Flow:
 * 1. Get clientId + folderId from our backend (/api/materials/upload-config)
 * 2. User signs in via Google Identity Services (one-time popup)
 * 3. Browser uploads file directly to Google Drive API using user's OAuth token
 * 4. Returns the Drive file ID
 */

const DriveUpload = {
    _tokenClient: null,
    _accessToken: null,
    _config: null,

    /**
     * Initialize by fetching config from backend and setting up the token client.
     */
    async init() {
        if (this._config) return; // already initialized

        // Get clientId + folderId from backend
        const res = await fetch('/api/materials/upload-config');
        if (!res.ok) throw new Error('Failed to get upload config');
        this._config = await res.json();

        // Wait for Google Identity Services library to load
        await this._waitForGis();

        // Initialize the OAuth2 token client
        this._tokenClient = google.accounts.oauth2.initTokenClient({
            client_id: this._config.clientId,
            scope: 'https://www.googleapis.com/auth/drive.file',
            callback: () => {} // will be overridden per-request
        });
    },

    /**
     * Wait for the Google Identity Services library to be available.
     */
    _waitForGis() {
        return new Promise((resolve, reject) => {
            if (typeof google !== 'undefined' && google.accounts) {
                resolve();
                return;
            }
            // Poll for availability (library loads async)
            let attempts = 0;
            const interval = setInterval(() => {
                attempts++;
                if (typeof google !== 'undefined' && google.accounts) {
                    clearInterval(interval);
                    resolve();
                } else if (attempts > 50) { // 5 seconds
                    clearInterval(interval);
                    reject(new Error('Google Identity Services library failed to load'));
                }
            }, 100);
        });
    },

    /**
     * Get an OAuth access token (prompts user to sign in if needed).
     */
    _getToken() {
        return new Promise((resolve, reject) => {
            this._tokenClient.callback = (response) => {
                if (response.error) {
                    reject(new Error(response.error));
                    return;
                }
                this._accessToken = response.access_token;
                resolve(response.access_token);
            };
            this._tokenClient.error_callback = (error) => {
                reject(new Error(error.message || 'OAuth authorization failed'));
            };

            if (this._accessToken) {
                // Try to use existing token, request new one silently
                this._tokenClient.requestAccessToken({ prompt: '' });
            } else {
                // First time: show consent popup
                this._tokenClient.requestAccessToken({ prompt: 'consent' });
            }
        });
    },

    /**
     * Upload a file to Google Drive.
     * @param {File} file - The file to upload
     * @param {function} onProgress - Callback with progress percentage (0-100)
     * @param {function} onSuccess - Callback with the Drive file ID
     * @param {function} onError - Callback with error message
     */
    async upload(file, onProgress, onSuccess, onError) {
        try {
            onProgress(0);

            // 1. Initialize (fetch config, set up token client)
            await this.init();

            // 2. Get user OAuth token (may show sign-in popup)
            const token = await this._getToken();

            // 3. Create a resumable upload session
            const metadata = JSON.stringify({
                name: file.name,
                parents: [this._config.folderId]
            });

            const sessionRes = await fetch(
                'https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable',
                {
                    method: 'POST',
                    headers: {
                        'Authorization': 'Bearer ' + token,
                        'Content-Type': 'application/json; charset=UTF-8',
                        'X-Upload-Content-Type': file.type,
                        'X-Upload-Content-Length': file.size
                    },
                    body: metadata
                }
            );

            if (!sessionRes.ok) {
                const errBody = await sessionRes.text();
                throw new Error('Failed to create upload session: ' + errBody);
            }

            const uploadUrl = sessionRes.headers.get('Location');
            if (!uploadUrl) {
                throw new Error('No upload URL returned by Google Drive');
            }

            // 4. Upload the file using XMLHttpRequest (for progress tracking)
            const xhr = new XMLHttpRequest();

            xhr.upload.addEventListener('progress', (e) => {
                if (e.lengthComputable) {
                    const percent = Math.round((e.loaded / e.total) * 100);
                    onProgress(percent);
                }
            });

            xhr.addEventListener('load', async () => {
                if (xhr.status >= 200 && xhr.status < 300) {
                    const response = JSON.parse(xhr.responseText);
                    // 5. Make the file viewable by anyone with the link (needed for iframe preview)
                    try {
                        await fetch(
                            'https://www.googleapis.com/drive/v3/files/' + response.id + '/permissions',
                            {
                                method: 'POST',
                                headers: {
                                    'Authorization': 'Bearer ' + token,
                                    'Content-Type': 'application/json'
                                },
                                body: JSON.stringify({
                                    role: 'reader',
                                    type: 'anyone'
                                })
                            }
                        );
                    } catch (permErr) {
                        console.warn('Could not set public permission:', permErr);
                    }
                    onSuccess(response.id);
                } else {
                    onError('Upload failed with status: ' + xhr.status);
                }
            });

            xhr.addEventListener('error', () => {
                onError('Upload failed due to a network error');
            });

            xhr.open('PUT', uploadUrl);
            xhr.setRequestHeader('Content-Type', file.type);
            xhr.send(file);

        } catch (err) {
            onError(err.message);
        }
    }
};
