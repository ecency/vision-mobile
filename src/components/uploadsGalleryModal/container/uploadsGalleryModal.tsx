import React, { forwardRef, useEffect, useImperativeHandle, useRef, useState } from 'react';
import { useIntl } from 'react-intl';
import { Alert, AlertButton } from 'react-native';
import ImagePicker, { Image, Options, Video } from 'react-native-image-crop-picker';
import RNHeicConverter from 'react-native-heic-converter';
import { ImageManipulator, SaveFormat } from 'expo-image-manipulator';
import { openSettings } from 'react-native-permissions';
import { SheetManager } from 'react-native-actions-sheet';
import * as Sentry from '@sentry/react-native';
import { captureException } from '../../../utils/sentryUtils';
import UploadsGalleryContent from '../children/uploadsGalleryContent';

import { useAppSelector } from '../../../hooks';
import { delay, extractFilenameFromPath, extractImageUrls } from '../../../utils/editor';
import { isMediaPickerCancellation, reportMediaPickerError } from '../../../utils/mediaPickerError';
import { readImageFromClipboard } from '../../../utils/clipboard';
import showLoginAlert from '../../../utils/showLoginAlert';
import { editorQueries } from '../../../providers/queries';
import { MediaItem } from '../../../providers/ecency/ecency.types';
import { SpeakUploaderModal } from '../children/speakUploaderModal';
import { SheetNames } from '../../../navigation/sheets';
import { selectIsLoggedIn } from '../../../redux/selectors';
import { MAX_IMAGE_UPLOAD_SIZE, isSignImageUnavailable } from '../../../constants/imageUpload';

import { MediaInsertContext, MediaInsertData, MediaInsertStatus, Modes } from '../types';
import {
  prepareInsertDispatch,
  registerPendingFlush,
  shouldQueueInsert,
} from '../mediaInsertQueue';
import { extractUploadPlaceholderNames } from '../uploadPlaceholder';

export { MediaInsertStatus, Modes } from '../types';
export type { MediaInsertContext, MediaInsertData } from '../types';

export interface UploadsGalleryModalRef {
  showModal: () => void;
}

const MAX_IMAGES_PER_PICK = 5;
const MAX_IMAGE_DIMENSION = 1920;
const COMPRESS_QUALITY = 0.85;
// Grace period between the editor reporting "typing stopped" and a queued insert
// rewriting the body, so keystrokes already queued natively land first.
const INSERT_SETTLE_MS = 100;

interface UploadsGalleryModalProps {
  postBody: string;
  paramFiles: any[];
  isEditing: boolean;
  isPreviewActive: boolean;
  allowMultiple?: boolean;
  hideToolbarExtension: () => void;
  handleMediaInsert: (data: Array<MediaInsertData>, context?: MediaInsertContext) => void;
  setIsUploading: (status: boolean) => void;
  /**
   * Receives the uploaded thumbnail of a 3Speak video along with the embed it belongs to,
   * so the thumbnail can be dropped again if the embed is removed from the body.
   */
  onVideoThumb?: (embedUrl: string, thumbUrl: string) => void;
}

export const UploadsGalleryModal = forwardRef(
  (
    {
      postBody,
      paramFiles,
      isEditing,
      isPreviewActive,
      allowMultiple,
      hideToolbarExtension,
      handleMediaInsert,
      setIsUploading,
      onVideoThumb,
    }: UploadsGalleryModalProps,
    ref,
  ) => {
    const intl = useIntl();

    const imageUploadsQuery = editorQueries.useMediaQuery();

    const mediaUploadMutation = editorQueries.useMediaUploadMutation();

    const pendingInserts = useRef<MediaInsertData[]>([]);
    const flushTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
    // Filenames whose "Uploading..." placeholder has been handed to the editor and
    // not yet resolved. Sent along with each batch so the editor can tell whether an
    // ambiguous placeholder might belong to a different upload.
    const inFlightPlaceholders = useRef<Set<string>>(new Set());
    const isEditingRef = useRef(isEditing);
    isEditingRef.current = isEditing;
    // Latest insert callback for code that outlives renders (upload continuations,
    // the deferred flush timer, the unmount flush below).
    const handleMediaInsertRef = useRef(handleMediaInsert);
    handleMediaInsertRef.current = handleMediaInsert;
    const speakUploaderRef = useRef<any>(null);

    const [showModal, setShowModal] = useState(false);
    const [isAddingToUploads, setIsAddingToUploads] = useState(false);
    const [mode, setMode] = useState<Modes>(Modes.MODE_IMAGE);
    const [mediaUrls, setMediaUrls] = useState<string[]>([]);
    const [isScrolledTop, setIsScrolledTop] = useState(true);

    const isLoggedIn = useAppSelector(selectIsLoggedIn);

    // Image gallery query (video gallery no longer needed with new embed architecture)
    const mediaUploadsQuery = imageUploadsQuery;
    const { fetchNextPage, hasNextPage, isFetchingNextPage } = mediaUploadsQuery;

    // Recover the placeholders already in the body. This component unmounts whenever
    // the user toggles preview, while its uploads keep running, so a fresh instance
    // would otherwise start with an empty in-flight set and tell the editor there
    // are no rival uploads — re-opening the very hole `otherPending` exists to close.
    // A placeholder that is actually dead only costs the optional repair path, never
    // correctness, and the draft sweep clears those on load anyway.
    const bodyAtMountRef = useRef(postBody);
    bodyAtMountRef.current = postBody;
    useEffect(() => {
      extractUploadPlaceholderNames(bodyAtMountRef.current).forEach((name) =>
        inFlightPlaceholders.current.add(name),
      );
    }, []);

    const _dispatchInserts = (data: MediaInsertData[], commitNow = false) => {
      const context = prepareInsertDispatch(inFlightPlaceholders.current, data);
      handleMediaInsertRef.current?.(data, { ...context, commitNow });
    };

    const _cancelScheduledFlush = () => {
      if (flushTimerRef.current) {
        clearTimeout(flushTimerRef.current);
        flushTimerRef.current = null;
      }
    };

    // `commitNow` is for teardown: the editor writes the body straight through
    // instead of on its 500ms debounce, so the draft save happening in the same
    // breath sees the resolved url rather than the placeholder.
    const _flushPendingInserts = (commitNow = false) => {
      _cancelScheduledFlush();
      if (!pendingInserts.current.length) {
        return;
      }
      const batch = pendingInserts.current;
      pendingInserts.current = [];
      _dispatchInserts(batch, commitNow);
    };

    const _scheduleFlush = () => {
      if (flushTimerRef.current || !pendingInserts.current.length) {
        return;
      }
      flushTimerRef.current = setTimeout(() => {
        flushTimerRef.current = null;
        if (isEditingRef.current) {
          // typing resumed inside the settle window; the next pause re-schedules
          return;
        }
        _flushPendingInserts();
      }, INSERT_SETTLE_MS);
    };

    useImperativeHandle(ref, () => ({
      toggleModal: (value: boolean, _mode: Modes = mode) => {
        if (!isLoggedIn) {
          showLoginAlert({ intl });
          return;
        }

        if (value === showModal && _mode === mode) {
          return;
        }

        if (value) {
          _getMediaUploads(_mode);
        }

        setMode(_mode);
        setShowModal(value);
      },
      getMode: () => mode,
      isVisible: () => showModal,
      isScrolledTop: () => {
        return isScrolledTop;
      },
      pasteImageFromClipboard: async () => {
        if (!isLoggedIn) {
          showLoginAlert({ intl });
          return;
        }
        try {
          const clipboardImage = await readImageFromClipboard();
          if (!clipboardImage) {
            Alert.alert(
              intl.formatMessage({ id: 'alert.fail' }),
              intl.formatMessage({ id: 'editor.clipboard_no_image' }),
            );
            return;
          }
          await _handleMediaOnSelected([clipboardImage as unknown as Image], true);
        } catch (error) {
          Sentry.captureException(error);
          Alert.alert(
            intl.formatMessage({ id: 'alert.fail' }),
            intl.formatMessage({ id: 'alert.something_wrong' }),
          );
        }
      },
    }));

    useEffect(() => {
      if (paramFiles) {
        console.log('files : ', paramFiles);

        // delay is a workaround to let editor ready before initiating uploads on mount
        delay(500).then(() => {
          const _mediaItems = paramFiles.map((el) => {
            if (el.filePath && el.fileName) {
              const _media = {
                path: el.filePath,
                mime: el.mimeType,
                filename: el.fileName,
              };

              return _media;
            }
            return null;
          });

          // Drop entries the map returned as null (shared files missing path/name) so the
          // size filter in _handleMediaOnSelected never dereferences a null item. A text-only
          // share filters to [], which would otherwise hit the "no media" error path, so only
          // invoke upload handling when something remains.
          const _validMediaItems = _mediaItems.filter(Boolean);
          if (_validMediaItems.length) {
            _handleMediaOnSelected(_validMediaItems as any, true);
          }
        });
      }
    }, [paramFiles]);

    useEffect(() => {
      // isEditing goes false exactly 500ms after the last keystroke — a natural
      // typing-pause cadence — and an insert rewrites the whole native text from the
      // JS-side refs. The scheduled flush lets queued native keystroke events drain
      // first, and re-defers if typing resumed (the next isEditing flip re-runs this
      // effect, so nothing is lost).
      if (!isEditing) {
        _scheduleFlush();
      }
      return _cancelScheduledFlush;
    }, [isEditing]);

    // The editor screen drains this before it saves on the way out. Its
    // `componentWillUnmount` runs ahead of every descendant's effect cleanup, so a
    // queue flushed only from the cleanup below lands after the draft has already
    // been written, and the resolved url misses that save.
    useEffect(() => registerPendingFlush(() => _flushPendingInserts(true)), []);

    useEffect(
      () => () => {
        // Deferred results must not die with this component: flush them so resolved
        // URLs still replace their placeholders in the editor refs and reach the
        // draft autosave (both stay live in closures past unmount). Then let any
        // upload still in flight insert directly on completion — with the component
        // gone there is no later isEditing flip to flush a deferral, so parking it
        // would orphan the placeholder in the saved draft.
        isEditingRef.current = false;
        _flushPendingInserts(true);
      },
      [],
    );

    useEffect(() => {
      _getMediaUploads(mode); // get media uploads when there is new update
    }, [mediaUploadsQuery.data, mode]);

    useEffect(() => {
      if (showModal) {
        const _urls = extractImageUrls({ body: postBody });
        setMediaUrls(_urls);
      }
    }, [postBody, showModal, mode]);

    const _handleOpenImagePicker = (addToUploads?: boolean, useDocumentPicker = false) => {
      const _vidMode = mode === Modes.MODE_VIDEO;

      if (_vidMode && isAddingToUploads) {
        speakUploaderRef.current.showUploader();
        return;
      }

      const _options: Options = _vidMode
        ? {
            mediaType: 'video',
            smartAlbums: ['UserLibrary', 'Favorites', 'Videos'],
          }
        : {
            // uploads read the file by path, base64 would only cost memory.
            // Single-image callers get a limit of one rather than multiple: false,
            // which makes the iOS picker re-encode the image as JPEG.
            multiple: true,
            maxFiles: allowMultiple === false ? 1 : MAX_IMAGES_PER_PICK,
            maxFileSize: MAX_IMAGE_UPLOAD_SIZE,
            mediaType: 'photo',
            smartAlbums: ['UserLibrary', 'Favorites', 'PhotoStream', 'Panoramas', 'Bursts'],
            useDocumentPicker,
          };

      ImagePicker.openPicker(_options)
        .then((items: any) => {
          if (items && !Array.isArray(items)) {
            items = [items];
          }
          if (_vidMode) {
            _handleVideoSelection(items[0]);
          } else {
            _handleMediaOnSelected(items, !addToUploads);
          }
        })
        .catch((e) => {
          _handleMediaOnSelectFailure(e, 'openPicker', _vidMode ? 'video' : 'photo');
        });
    };

    const _handleOpenCamera = () => {
      const _vidMode = mode === Modes.MODE_VIDEO;

      if (_vidMode && isAddingToUploads) {
        speakUploaderRef.current.showUploader();
        return;
      }

      const _options: Options = _vidMode
        ? {
            mediaType: 'video',
          }
        : {
            mediaType: 'photo',
          };

      ImagePicker.openCamera(_options)
        .then((media: any) => {
          if (_vidMode) {
            _handleVideoSelection(media);
          } else {
            _handleMediaOnSelected([media], true);
          }
        })
        .catch((e) => {
          _handleMediaOnSelectFailure(e, 'openCamera', _vidMode ? 'video' : 'photo');
        });
    };

    const _handleMediaOnSelected = async (media: Image[], shouldInsert: boolean) => {
      try {
        if (!media || media.length == 0) {
          throw new Error('New media items returned');
        }

        // Gate before any placeholder is written: a logged-out upload can never
        // resolve, so it must never put an "Uploading..." placeholder in the body.
        // Reachable logged-out via the share-files intent (other entry points
        // already gate in toggleModal/pasteImageFromClipboard).
        if (!isLoggedIn) {
          showLoginAlert({ intl });
          return;
        }

        // filter out oversized images (server limit is 30MB)
        const oversized = media.filter(
          (item) => item && item.size && item.size > MAX_IMAGE_UPLOAD_SIZE,
        );
        if (oversized.length > 0) {
          media = media.filter(
            (item) => item && (!item.size || item.size <= MAX_IMAGE_UPLOAD_SIZE),
          );
          Alert.alert(
            intl.formatMessage({ id: 'alert.fail' }),
            intl.formatMessage({ id: 'alert.payloadTooLarge' }),
          );
          if (media.length === 0) {
            return;
          }
        }

        // post process media items: convert HEIC and compress non-GIF images
        for (let i = 0; i < media.length; i++) {
          const element = media[i];

          // convert HEIC to JPEG
          if (element.mime === 'image/heic') {
            // eslint-disable-next-line no-await-in-loop
            const res = await RNHeicConverter.convert({ path: element.sourceURL });
            if (res && res.path) {
              element.mime = 'image/jpeg';
              element.path = res.path;
              element.filename = element.filename ? element.filename.replace('.HEIC', '.JPG') : '';
              media[i] = element;
            }
          }

          // compress non-GIF images that exceed max dimensions (skip GIFs to preserve animation)
          if (
            element.mime !== 'image/gif' &&
            (element.width > MAX_IMAGE_DIMENSION || element.height > MAX_IMAGE_DIMENSION)
          ) {
            const resizeOpt =
              element.width >= element.height
                ? { width: MAX_IMAGE_DIMENSION }
                : { height: MAX_IMAGE_DIMENSION };
            // eslint-disable-next-line no-await-in-loop
            const imageRef = await ImageManipulator.manipulate(element.path)
              .resize(resizeOpt)
              .renderAsync();
            // eslint-disable-next-line no-await-in-loop
            const result = await imageRef.saveAsync({
              compress: COMPRESS_QUALITY,
              format: SaveFormat.JPEG,
            });
            element.path = result.uri;
            element.width = result.width;
            element.height = result.height;
            element.mime = 'image/jpeg';
            if (element.filename) {
              element.filename = element.filename.replace(/\.[^.]+$/, '.jpg');
            }
            media[i] = element;
          }
        }

        if (shouldInsert) {
          setShowModal(false);
          hideToolbarExtension();
          // Batch all UPLOADING placeholders into a single insert call
          // to avoid race conditions from multiple sequential handleMediaInsert calls
          const uploadingInserts: MediaInsertData[] = [];
          media.forEach((element, index) => {
            if (element) {
              media[index].filename =
                element.filename ||
                extractFilenameFromPath({ path: element.path, mimeType: element.mime });
              uploadingInserts.push({
                filename: element.filename || '',
                url: '',
                text: '',
                status: MediaInsertStatus.UPLOADING,
              });
            }
          });
          if (uploadingInserts.length > 0) {
            // Through the deferral gate like every other programmatic insert, so a
            // body being typed into or restored is never overwritten mid-flight
            // (matters for the share-intent path, which fires on a timer at mount).
            _handleMediaInsertion(uploadingInserts);
          }
        }

        if (setIsUploading) {
          setIsUploading(true);
        }
        if (!shouldInsert) {
          setIsAddingToUploads(true);
        }

        const results = await Promise.all(
          media.map((element) =>
            element
              ? _uploadImage(element, { shouldInsert })
                  .then((value) => ({ status: 'fulfilled' as const, value }))
                  .catch((reason) => ({ status: 'rejected' as const, reason }))
              : Promise.resolve({ status: 'fulfilled' as const, value: undefined }),
          ),
        );

        // Batch insert all successful uploads in a single call to avoid race conditions
        // where parallel onSuccess callbacks read stale body text from refs. Every
        // placeholder written above must get a verdict here: an upload that resolved
        // without a url (or was skipped because the session ended mid-flight) throws
        // nothing, so without the FAILED fallback its placeholder would stay in the
        // body forever with no result left to resolve it. Rejections already emitted
        // their own FAILED from _uploadImage, so they are not repeated here.
        if (shouldInsert) {
          const resolvedInserts = results
            .map((result, index) => {
              if (!media[index] || result.status !== 'fulfilled') {
                return null;
              }
              return result.value?.url
                ? {
                    filename: media[index]?.filename || '',
                    url: result.value.url,
                    text: '',
                    status: MediaInsertStatus.READY,
                  }
                : {
                    filename: media[index]?.filename || '',
                    url: '',
                    text: '',
                    status: MediaInsertStatus.FAILED,
                  };
            })
            .filter(Boolean);

          if (resolvedInserts.length > 0) {
            _handleMediaInsertion(resolvedInserts as any);
          }
        }

        // Collect all errors and show a single alert if any uploads failed
        const failures = results.filter((result) => result.status === 'rejected');
        if (failures.length > 0) {
          const errorMessages = new Set<string>();
          failures.forEach((failure) => {
            const error = failure.status === 'rejected' ? failure.reason : failure;
            if (isSignImageUnavailable(error)) {
              errorMessages.add(
                intl.formatMessage({
                  id: 'alert.decrypt_fail_alert',
                }),
              );
            } else if (error.toString().includes('code 413')) {
              errorMessages.add(
                intl.formatMessage({
                  id: 'alert.payloadTooLarge',
                }),
              );
            } else if (error.toString().includes('code 429')) {
              errorMessages.add(
                intl.formatMessage({
                  id: 'alert.quotaExceeded',
                }),
              );
            } else if (error.toString().includes('code 400')) {
              errorMessages.add(
                intl.formatMessage({
                  id: 'alert.invalidImage',
                }),
              );
            } else {
              captureException(error, (scope) => scope.setTag('context', 'media-upload-batch'));
              errorMessages.add(error.message || intl.formatMessage({ id: 'alert.unknow_error' }));
            }
          });

          const aggregatedMessage =
            failures.length > 1
              ? `${failures.length} uploads failed:\n\n${Array.from(errorMessages).join('\n')}`
              : Array.from(errorMessages)[0];

          Alert.alert(
            intl.formatMessage({
              id: 'alert.fail',
            }),
            aggregatedMessage,
          );
        }
      } catch (error) {
        console.log('Failed to upload image', error);

        Sentry.captureException(error);
      } finally {
        if (setIsUploading) {
          setIsUploading(false);
        }
        setIsAddingToUploads(false);
      }
    };

    const _uploadImage = async (media: any, { shouldInsert } = { shouldInsert: false }) => {
      if (!isLoggedIn) {
        // Defensive: callers gate on login before any placeholder is written. If the
        // session ended mid-flight and one exists, returning no url marks it FAILED
        // in the batch above rather than leaving it stuck.
        return undefined;
      }
      try {
        const data = await mediaUploadMutation.mutateAsync({
          media,
          addToUploads: !shouldInsert,
        });
        console.log('upload successfully', data, media, shouldInsert);
        // Return upload result for batched insertion by caller
        return data;
      } catch (error) {
        console.log('error while uploading image : ', error);

        if (shouldInsert) {
          _handleMediaInsertion([
            {
              filename: media.filename,
              url: '',
              text: '',
              status: MediaInsertStatus.FAILED,
            },
          ]);
        }

        // Re-throw error to be caught by .catch wrapper in _handleMediaOnSelected
        throw error;
      }
    };

    const _handleVideoSelection = (video: Video) => {
      // show video upload modal,
      // allow thumbnail selection and uplaods
      speakUploaderRef.current.showUploader(video);
    };

    const _handleMediaOnSelectFailure = (
      error: any,
      action: 'openPicker' | 'openCamera' = 'openPicker',
      mediaType: 'photo' | 'video' | 'mixed' = 'photo',
    ) => {
      if (isMediaPickerCancellation(error)) {
        return;
      }

      // more files than the limit is the user's choice, not a picker failure
      const _tooManyFiles = error.code === 'E_TOO_MANY_FILES';
      if (!_tooManyFiles) {
        reportMediaPickerError(error, {
          feature: 'editor-uploads-modal',
          action,
          mediaType,
        });
      }

      let title = intl.formatMessage({ id: 'alert.something_wrong' });
      let body = _tooManyFiles
        ? intl.formatMessage({ id: 'alert.too_many_images' }, { count: MAX_IMAGES_PER_PICK })
        : error.message || JSON.stringify(error);
      let dialogAction: AlertButton = {
        text: intl.formatMessage({ id: 'alert.okay' }),
        onPress: () => {
          console.log('cancel pressed');
        },
      };

      switch (error.code) {
        case 'E_PERMISSION_MISSING':
        case 'E_NO_LIBRARY_PERMISSION':
        case 'E_NO_CAMERA_PERMISSION':
          title = intl.formatMessage({
            id: 'alert.permission_denied',
          });
          body = intl.formatMessage({
            id: 'alert.permission_text',
          });
          dialogAction = {
            text: intl.formatMessage({ id: 'alert.open_settings' }),
            onPress: () => {
              openSettings();
            },
          };
          break;
      }

      // SheetManager.show is self-executing and returns a Promise, not a Redux action;
      // dispatching it threw Redux error #7 ("Actions may not have an undefined type").
      SheetManager.show(SheetNames.ACTION_MODAL, {
        payload: {
          title,
          body,
          buttons: [dialogAction],
        },
      });
    };

    const _handleOpenSpeakUploader = () => {
      speakUploaderRef.current.showUploader();
    };

    const _setIsSpeakUploading = (flag: boolean) => {
      setIsUploading(flag);
      setIsAddingToUploads(flag);
    };

    const _handleMediaInsertion = (data: MediaInsertData[]) => {
      if (shouldQueueInsert(isEditingRef.current, pendingInserts.current.length)) {
        pendingInserts.current.push(...data);
        if (!isEditingRef.current) {
          _scheduleFlush();
        }
        return;
      }
      _dispatchInserts(data);
    };

    // fetch images from server
    const _getMediaUploads = async (_mode: Modes = mode) => {
      try {
        mediaUploadsQuery.refetch();
      } catch (err) {
        console.warn('Failed to get images');
      }
      setIsAddingToUploads(false);
    };

    // inserts media items in post body
    const _insertMedia = async (map: Map<number, boolean>) => {
      const data: MediaInsertData[] = [];

      map.forEach((value, index) => {
        console.log(index);
        const item: MediaItem = mediaUploadsQuery.data[index] as any;
        data.push({
          url: item.url,
          text: '',
          status: MediaInsertStatus.READY,
          mode,
        });
      });

      // Through the same gate as every other insert: these carry no placeholder and
      // land at the caret, so they must not overtake a queued placeholder either.
      _handleMediaInsertion(data);
    };

    const data = mediaUploadsQuery.data.slice();

    if (isPreviewActive) {
      return null;
    }

    return (
      <>
        {showModal && (
          <UploadsGalleryContent
            mode={mode}
            insertedMediaUrls={mediaUrls}
            mediaUploads={data as any}
            isAddingToUploads={isAddingToUploads}
            insertMedia={_insertMedia}
            handleOpenCamera={_handleOpenCamera}
            handleOpenGallery={_handleOpenImagePicker}
            handleOpenFiles={() => _handleOpenImagePicker(false, true)}
            handleOpenSpeakUploader={_handleOpenSpeakUploader}
            handleIsScrolledTop={setIsScrolledTop}
            // Pagination props
            fetchNextPage={fetchNextPage}
            hasNextPage={hasNextPage}
            isFetchingNextPage={isFetchingNextPage}
          />
        )}
        <SpeakUploaderModal
          ref={speakUploaderRef}
          isUploading={isAddingToUploads}
          setIsUploading={_setIsSpeakUploading}
          onVideoUploaded={(embedUrl, thumbnailUrl) => {
            _handleMediaInsertion([
              {
                url: embedUrl,
                text: '',
                status: MediaInsertStatus.READY,
                mode: Modes.MODE_VIDEO,
              },
            ]);
            if (thumbnailUrl) {
              onVideoThumb?.(embedUrl, thumbnailUrl);
            }
          }}
        />
      </>
    );
  },
);
