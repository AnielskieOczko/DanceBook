/**
 * Google Drive Direct Upload Module (Server-side auth version)
 * 
 * Flow:
 * 1. Get access token + folderId from backend (backend uses stored refresh token)
 * 2. Create resumable upload session (via backend or directly with token)
 * 3. Browser uploads file directly to Google Drive using the pre-auth URL
 * 4. Finalize: set public permission via backend
 * 5. Return the Drive file ID
 * 
 * NO Google popup, NO user sign-in required.
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

            // 1. Create a resumable upload session via backend
            //    (backend authenticates with Google using stored refresh token)
            const sessionRes = await fetch('/api/materials/upload-session', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
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

            const { uploadUrl } = await sessionRes.json();

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

                    // 3. Finalize: set public permission via backend
                    try {
                        await fetch('/api/materials/finalize-upload', {
                            method: 'POST',
                            headers: { 'Content-Type': 'application/json' },
                            body: JSON.stringify({ fileId: fileId })
                        });
                    } catch (permErr) {
                        console.warn('Could not set public permission:', permErr);
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
