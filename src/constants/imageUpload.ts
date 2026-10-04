export const MAX_IMAGE_UPLOAD_SIZE = 30000000; // 30MB server limit

// Thrown when an upload signature cannot be produced: the stored posting key or
// access token could not be decrypted with the current PIN, or the account has
// neither. Callers should ask the user to re-login rather than attempt the upload.
export const SIGN_IMAGE_UNAVAILABLE = 'SIGN_IMAGE_UNAVAILABLE';

export const isSignImageUnavailable = (error: unknown): boolean =>
  (error as { message?: string })?.message === SIGN_IMAGE_UNAVAILABLE;
