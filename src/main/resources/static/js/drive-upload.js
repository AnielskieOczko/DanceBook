/**
 * Google Drive Direct Upload Module
 * 
 * Flow:
 * 1. Request upload session from DanceBook backend (no Google tokens sent to browser)
 * 2. Browser uploads file directly to Google Drive using the pre-auth resumable URL
 * 3. Finalize: verify uploader on backend and record uploaded file
 * 4. Return the Drive file ID
 * 
 * NO Google credentials ever reach the browser.
 */

const DriveUpload = {

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

            // Read CSRF tokens from meta tags
            const csrfToken = document.querySelector('meta[name="_csrf"]')?.getAttribute('content');
            const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.getAttribute('content');
            const authHeaders = { 'Content-Type': 'application/json' };
            if (csrfToken && csrfHeader) {
                authHeaders[csrfHeader] = csrfToken;
            }

            // 1. Create resumable upload session via DanceBook backend
            const sessionRes = await fetch('/api/materials/upload-session', {
                method: 'POST',
                headers: authHeaders,
                body: JSON.stringify({
                    fileName: file.name,
                    mimeType: file.type || 'video/mp4',
                    fileSize: file.size
                })
            });

            if (!sessionRes.ok) {
                const errText = await sessionRes.text();
                throw new Error('Failed to create upload session: ' + errText);
            }

            const sessionData = await sessionRes.json();
            const uploadUrl = sessionData.uploadUrl;
            if (!uploadUrl) {
                throw new Error('No upload URL returned by server');
            }

            // 2. Upload the file directly to Google Drive using the pre-auth URL
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
                    const fileId = response.id;

                    // 3. Finalize: verify and record upload on backend
                    try {
                        const finRes = await fetch('/api/materials/finalize-upload', {
                            method: 'POST',
                            headers: authHeaders,
                            body: JSON.stringify({ fileId: fileId })
                        });
                        if (!finRes.ok) {
                            onError('Finalize upload failed with status: ' + finRes.status);
                            return;
                        }
                    } catch (permErr) {
                        onError('Could not finalize upload: ' + permErr.message);
                        return;
                    }

                    onSuccess(fileId);
                } else {
                    onError('Upload failed with status: ' + xhr.status);
                }
            });

            xhr.addEventListener('error', () => {
                onError('Upload failed due to a network error');
            });

            xhr.open('PUT', uploadUrl);
            xhr.setRequestHeader('Content-Type', file.type || 'video/mp4');
            xhr.send(file);

        } catch (err) {
            onError(err.message);
        }
    }
};
