import React, { PureComponent } from 'react';
import { Platform, View } from 'react-native';
import { injectIntl } from 'react-intl';
import get from 'lodash/get';

import { gestureHandlerRootHOC } from 'react-native-gesture-handler';
import { ProfileEditContainer } from '../../../containers';

import {
  EditAvatar,
  Header,
  ProfileEditForm as ProfileEditFormRaw,
} from '../../../components/index';
import { OptionsModal } from '../../../components/atoms';
import styles from './profileEditScreenStyles';

const ProfileEditForm: any = ProfileEditFormRaw;

// The system document picker, for Android devices whose photo picker is broken.
const SHOW_FILES_SOURCE = Platform.OS === 'android';

class ProfileEditScreen extends PureComponent<any, any> {
  galleryRef: any;

  /* Props
   * ------------------------------------------------
   *   @prop { type }    name                - Description....
   */

  constructor(props: any) {
    super(props);
    this.state = {
      selectedUploadAction: '',
    };

    this.galleryRef = React.createRef();
  }

  // Component Life Cycles

  // Component Functions
  _showImageUploadActions = (action: any) => {
    this.setState({ selectedUploadAction: action }, () => {
      this.galleryRef.current.show();
    });
  };

  render() {
    const { intl, route } = this.props;
    const { selectedUploadAction } = this.state;

    return (
      <ProfileEditContainer route={route}>
        {({
          currentAccount,
          isDarkTheme,
          formData,
          handleOnItemChange,
          handleMediaAction,
          name,
          location,
          website,
          about,
          avatarUrl,
          coverUrl,
          isLoading,
          isUploading,
          saveEnabled,
          handleOnSubmit,
        }: any) => (
          <View style={styles.container}>
            <Header isReverse={true} />
            <EditAvatar
              username={get(currentAccount, 'name')}
              name={name}
              reputation={get(currentAccount, 'reputation')}
              avatarUrl={avatarUrl}
              showImageUploadActions={() => this._showImageUploadActions('avatarUrl')}
              isUploading={isUploading && selectedUploadAction === 'avatarUrl'}
            />
            <ProfileEditForm
              formData={formData}
              isDarkTheme={isDarkTheme}
              about={about}
              name={name}
              location={location}
              website={website}
              coverUrl={coverUrl}
              showImageUploadActions={(() => this._showImageUploadActions('coverUrl')) as any}
              handleOnItemChange={handleOnItemChange}
              isLoading={isLoading}
              isUploading={isUploading && selectedUploadAction === 'coverUrl'}
              saveEnabled={saveEnabled}
              handleOnSubmit={handleOnSubmit}
            />

            <OptionsModal
              ref={this.galleryRef}
              options={[
                intl.formatMessage({
                  id: 'editor.open_gallery',
                }),
                intl.formatMessage({
                  id: 'editor.capture_photo',
                }),
                ...(SHOW_FILES_SOURCE ? [intl.formatMessage({ id: 'editor.open_files' })] : []),
                intl.formatMessage({
                  id: 'alert.cancel',
                }),
              ]}
              cancelButtonIndex={SHOW_FILES_SOURCE ? 3 : 2}
              onPress={(index) => {
                handleMediaAction(
                  index === 0
                    ? 'image'
                    : index === 1
                    ? 'camera'
                    : index === 2 && SHOW_FILES_SOURCE && 'files',
                  selectedUploadAction,
                );
              }}
            />
          </View>
        )}
      </ProfileEditContainer>
    );
  }
}

export default gestureHandlerRootHOC(injectIntl(ProfileEditScreen));
