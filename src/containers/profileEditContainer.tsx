import React, { Component } from 'react';
import { Alert } from 'react-native';
import { connect } from 'react-redux';
import { injectIntl } from 'react-intl';
import ImagePicker from 'react-native-image-crop-picker';
import get from 'lodash/get';

import { useNavigation } from '@react-navigation/native';
import { captureException } from '../utils/sentryUtils';
import { selectCurrentAccount, selectIsDarkTheme, selectPin } from '../redux/selectors';
import { uploadImage } from '../providers/ecency/ecency';

import { signImage } from '../providers/hive/hive';
import { MAX_IMAGE_UPLOAD_SIZE, isSignImageUnavailable } from '../constants/imageUpload';
import { useAccountUpdateMutation } from '../providers/sdk/mutations';
import { updateCurrentAccount } from '../redux/actions/accountAction';
import { setAvatarCacheStamp } from '../redux/actions/uiAction';
import { isMediaPickerCancellation, reportMediaPickerError } from '../utils/mediaPickerError';

// import ROUTES from '../constants/routeNames';

const FORM_DATA = [
  {
    valueKey: 'name',
    type: 'text',
    label: 'display_name',
    placeholder: '',
  },
  {
    valueKey: 'about',
    type: 'text',
    label: 'about',
    placeholder: '',
  },
  {
    valueKey: 'location',
    type: 'text',
    label: 'location',
    placeholder: '',
  },
  {
    valueKey: 'website',
    type: 'text',
    label: 'website',
    placeholder: '',
  },
];

class ProfileEditContainer extends Component<any, any> {
  /* Props
   * ------------------------------------------------
   *   @prop { type }    name                - Description....
   */

  constructor(props: any) {
    super(props);
    const profile = props.currentAccount.profile || {};
    this.state = {
      isLoading: false,
      isUploading: false,
      saveEnabled: false,
      about: profile.about,
      name: profile.name,
      location: profile.location,
      website: profile.website,
      coverUrl: profile.cover_image,
      pinned: profile.pinned,
      avatarUrl: get(props.currentAccount, 'avatar'),
    };
  }

  // Component Life Cycles

  // Component Functions

  _handleOnItemChange = (val: any, item: any) => {
    this.setState({ [item]: val, saveEnabled: true });
  };

  _uploadImage = async (media: any, action: any) => {
    const { intl, currentAccount, pinCode } = this.props;

    this.setState({ isUploading: true });

    let sign;
    try {
      sign = await signImage(media, currentAccount, pinCode);
    } catch (error) {
      this.setState({ isUploading: false });
      Alert.alert(
        intl.formatMessage({ id: 'alert.fail' }),
        intl.formatMessage({
          id: isSignImageUnavailable(error) ? 'alert.decrypt_fail_alert' : 'alert.unknow_error',
        }),
      );
      return;
    }

    uploadImage(media, currentAccount.name, sign)
      .then((res) => {
        if (res.data && res.data.url) {
          this.setState({ [action]: res.data.url, isUploading: false }, () => {
            // submit after img upload
            this._handleOnSubmit({ goBack: false });
          });
        } else if (res && res.url) {
          this.setState({ [action]: res.url, isUploading: false }, () => {
            // submit after img upload
            this._handleOnSubmit({ goBack: false });
          });
        } else {
          throw Error(
            intl.formatMessage({
              id: 'alert.unknow_error',
            }),
          );
        }
      })
      .catch((error) => {
        if (error) {
          captureException(error, (scope) => scope.setTag('context', 'profile-edit-image-upload'));
          Alert.alert(
            intl.formatMessage({
              id: 'alert.fail',
            }),
            error.message || intl.formatMessage({ id: 'alert.unknow_error' }),
          );
        }
        this.setState({ isUploading: false });
      });
  };

  _handleMediaAction = (type: any, uploadAction: any) => {
    if (type === 'camera') {
      this._handleOpenCamera(uploadAction);
    } else if (type === 'image') {
      this._handleOpenImagePicker(uploadAction);
    } else if (type === 'files') {
      this._handleOpenImagePicker(uploadAction, true);
    }
  };

  // An image above maxFileSize comes back as its size only, it was never read.
  // One the picker had no size for up front is caught here by its real size.
  _rejectTooLarge = (media: any) => {
    const { intl } = this.props;

    if (media?.path && !(media.size > MAX_IMAGE_UPLOAD_SIZE)) {
      return false;
    }

    Alert.alert(
      intl.formatMessage({ id: 'alert.fail' }),
      intl.formatMessage({ id: 'alert.payloadTooLarge' }),
    );
    return true;
  };

  _handleOpenImagePicker = (action: any, useDocumentPicker = false) => {
    ImagePicker.openPicker({
      ...(action == 'avatarUrl' ? IMAGE_PICKER_AVATAR_OPTIONS : IMAGE_PICKER_COVER_OPTIONS),
      mediaType: 'photo',
      useDocumentPicker,
    })
      .then((media) => {
        if (!this._rejectTooLarge(media)) {
          this._uploadImage(media, action);
        }
      })
      .catch((e) => {
        this._handleMediaOnSelectFailure(e, 'openPicker');
      });
  };

  _handleOpenCamera = (action: any) => {
    ImagePicker.openCamera(
      action == 'avatarUrl' ? IMAGE_PICKER_AVATAR_OPTIONS : IMAGE_PICKER_COVER_OPTIONS,
    )
      .then((media) => {
        if (!this._rejectTooLarge(media)) {
          this._uploadImage(media, action);
        }
      })
      .catch((e) => {
        this._handleMediaOnSelectFailure(e, 'openCamera');
      });
  };

  _handleMediaOnSelectFailure = (error: any, action: any = 'openPicker') => {
    const { intl } = this.props;

    if (isMediaPickerCancellation(error)) {
      return;
    }

    reportMediaPickerError(error, {
      feature: 'profile-edit',
      action,
      mediaType: 'photo',
    });

    if (PICKER_PERMISSION_ERRORS.includes(get(error, 'code'))) {
      Alert.alert(
        intl.formatMessage({
          id: 'alert.permission_denied',
        }),
        intl.formatMessage({
          id: 'alert.permission_text',
        }),
      );
    } else {
      // a failed pick must not look like nothing happened
      Alert.alert(
        intl.formatMessage({ id: 'alert.fail' }),
        get(error, 'message') || intl.formatMessage({ id: 'alert.unknow_error' }),
      );
    }
  };

  _handleOnSubmit = async ({ goBack }: any) => {
    const { currentAccount, dispatch, navigation, intl, route } = this.props;
    const { name, location, website, about, coverUrl, avatarUrl, pinned } = this.state;

    this.setState({ isLoading: true });

    // Preserve all existing profile fields and only update the ones being edited
    const params = {
      ...(currentAccount.profile || {}),
      profile_image: avatarUrl,
      cover_image: coverUrl,
      name,
      website,
      about,
      location,
      pinned,
      version: 2,
    };

    try {
      const { accountUpdateMutation } = this.props;
      await accountUpdateMutation.mutateAsync({ profile: params });

      const _currentAccount = { ...currentAccount, display_name: name, avatar: avatarUrl };
      _currentAccount.profile = params;

      dispatch(updateCurrentAccount(_currentAccount));
      dispatch(setAvatarCacheStamp(new Date().getTime()));
      this.setState({ isLoading: false });
      route.params.fetchUser();
      if (goBack) {
        navigation.goBack();
      }
    } catch (err) {
      Alert.alert(
        intl.formatMessage({
          id: 'alert.fail',
        }),
        get(err, 'message', (err as any).toString()),
      );
      this.setState({ isLoading: false });
    }
  };

  render() {
    const { children, currentAccount, isDarkTheme, navigation } = this.props;
    const {
      isLoading,
      isUploading,
      name,
      location,
      website,
      about,
      coverUrl,
      avatarUrl,
      saveEnabled,
    } = this.state;

    return (
      children &&
      children({
        about,
        avatarUrl,
        coverUrl,
        currentAccount,
        formData: FORM_DATA,
        handleMediaAction: this._handleMediaAction,
        handleOnItemChange: this._handleOnItemChange,
        handleOnSubmit: this._handleOnSubmit,
        isDarkTheme,
        isLoading,
        isUploading,
        location,
        name,
        website,
        saveEnabled,
        navigation,
      })
    );
  }
}

const mapStateToProps = (state: any) => ({
  currentAccount: selectCurrentAccount(state),
  isDarkTheme: selectIsDarkTheme(state),
  pinCode: selectPin(state),
});

const mapHooksToProps = (props: any) => {
  const navigation = useNavigation();
  const accountUpdateMutation = useAccountUpdateMutation();
  return (
    <ProfileEditContainer
      {...props}
      navigation={navigation}
      accountUpdateMutation={accountUpdateMutation}
    />
  );
};

export default connect(mapStateToProps)(injectIntl(mapHooksToProps));

// the codes the picker rejects with when a permission is denied
const PICKER_PERMISSION_ERRORS = [
  'E_PERMISSION_MISSING',
  'E_NO_LIBRARY_PERMISSION',
  'E_NO_CAMERA_PERMISSION',
];

// uploads read the file by path, so no base64
const IMAGE_PICKER_AVATAR_OPTIONS = {
  maxFileSize: MAX_IMAGE_UPLOAD_SIZE,
  cropping: true,
  width: 512,
  height: 512,
};

const IMAGE_PICKER_COVER_OPTIONS = {
  maxFileSize: MAX_IMAGE_UPLOAD_SIZE,
};
