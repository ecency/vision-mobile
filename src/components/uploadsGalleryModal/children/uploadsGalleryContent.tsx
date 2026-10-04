import React, { useEffect, useState } from 'react';
import { useIntl } from 'react-intl';
import {
  ActivityIndicator,
  Alert,
  Keyboard,
  NativeScrollEvent,
  NativeSyntheticEvent,
  Platform,
  Text,
  TouchableOpacity,
  View,
  ViewStyle,
  ViewToken,
} from 'react-native';
import EStyleSheet from 'react-native-extended-stylesheet';
import Animated, {
  default as AnimatedView,
  Easing,
  SlideInRight,
  SlideOutRight,
  useSharedValue,
  withTiming,
} from 'react-native-reanimated';
import { FlatList } from 'react-native-gesture-handler';
import { Icon, IconButton } from '../..';
import { MediaItem } from '../../../providers/ecency/ecency.types';
import { editorQueries } from '../../../providers/queries';
import { MediaPreviewItem } from './mediaPreviewItem';
import styles, {
  COMPACT_HEIGHT,
  EXPANDED_HEIGHT,
  MAX_HORIZONTAL_THUMBS,
} from './uploadsGalleryModalStyles';
import { Modes } from '../container/uploadsGalleryModal';

// The system document picker, for Android devices whose photo picker is broken.
// Images only: the picker library copies a document on the UI thread, too slow for videos.
const HAS_FILES_SOURCE = Platform.OS === 'android';

type Props = {
  mode: Modes;
  insertedMediaUrls: string[];
  mediaUploads: MediaItem[];
  isAddingToUploads: boolean;
  insertMedia: (map: Map<number, boolean>) => void;
  handleOpenGallery: (addToUploads?: boolean) => void;
  handleOpenFiles: () => void;
  handleOpenSpeakUploader: () => void;
  handleOpenCamera: () => void;
  handleIsScrolledTop: (isScrolledTop: boolean) => void;
  fetchNextPage?: () => void;
  hasNextPage?: boolean;
  isFetchingNextPage?: boolean;
};

const UploadsGalleryContent = ({
  mode,
  insertedMediaUrls,
  mediaUploads,
  isAddingToUploads,
  insertMedia,
  handleOpenGallery,
  handleOpenFiles,
  handleOpenCamera,
  handleOpenSpeakUploader,
  handleIsScrolledTop,
  fetchNextPage,
  hasNextPage,
  isFetchingNextPage,
}: Props) => {
  const intl = useIntl();

  const deleteMediaMutation = editorQueries.useMediaDeleteMutation();

  const [deleteIds, setDeleteIds] = useState<string[]>([]);
  const [isDeleteMode, setIsDeleteMode] = useState(false);
  const [isExpandedMode, setIsExpandedMode] = useState(false);
  const [viewableItemsMap, setViewableItemsMap] = useState<{ [index: number]: boolean }>({});

  const animatedHeight = useSharedValue(0);

  useEffect(() => {
    animatedHeight.value = withTiming(COMPACT_HEIGHT, {
      easing: Easing.inOut(Easing.cubic),
    });
  }, []);

  const isDeleting = deleteMediaMutation.isPending;

  useEffect(() => {
    if (isExpandedMode) {
      Keyboard.dismiss();
    }
  }, [isExpandedMode]);

  const _deleteMedia = async () => {
    const _options = {
      onSettled: () => {
        setIsDeleteMode(false);
        setDeleteIds([]);
      },
    };

    deleteMediaMutation.mutate(deleteIds, _options);
  };

  const _onDeletePress = async () => {
    if (isDeleteMode && deleteIds.length > 0) {
      const _onCancelPress = () => {
        setIsDeleteMode(false);
        setDeleteIds([]);
      };

      Alert.alert(
        intl.formatMessage({ id: 'alert.delete' }),
        intl.formatMessage({ id: 'uploads_modal.confirm_delete' }),
        [
          {
            text: intl.formatMessage({ id: 'alert.cancel' }),
            style: 'cancel',
            onPress: _onCancelPress,
          },
          {
            text: intl.formatMessage({ id: 'alert.confirm' }),
            onPress: () => _deleteMedia(),
          },
        ],
      );
    } else {
      setIsDeleteMode(!isDeleteMode);
    }
  };

  const _onScroll = (event: NativeSyntheticEvent<NativeScrollEvent>) => {
    const { contentOffset } = event.nativeEvent;
    handleIsScrolledTop(contentOffset.y <= 0);
  };

  const _handleLoadMore = () => {
    if (isExpandedMode && hasNextPage && !isFetchingNextPage && fetchNextPage) {
      fetchNextPage();
    }
  };

  // render list item for snippet and handle actions;
  const _renderItem = ({ item, index }: { item: MediaItem; index: number }) => {
    // Video items no longer appear in the gallery (new embed architecture)

    const _onPress = () => {
      if (isDeleteMode) {
        const deleteId = item._id;

        const idIndex = deleteIds.indexOf(deleteId);
        if (idIndex >= 0) {
          deleteIds.splice(idIndex, 1);
        } else {
          deleteIds.push(deleteId);
        }
        setDeleteIds([...deleteIds]);
      } else {
        // New embed architecture: no video status checks needed, just insert
        insertMedia(new Map([[index, true]]));
      }
    };

    return (
      <MediaPreviewItem
        item={item}
        insertedMediaUrls={insertedMediaUrls}
        deleteIds={deleteIds}
        isDeleteMode={isDeleteMode}
        isDeleting={isDeleting}
        isExpandedMode={isExpandedMode}
        isViewable={viewableItemsMap[index] || false}
        onPress={_onPress}
      />
    );
  };

  const _renderSelectButton = (iconName: string, text: string, onPress: () => void) => {
    return (
      <TouchableOpacity
        onPress={() => {
          onPress && onPress();
        }}
      >
        <View style={styles.selectButton}>
          <View style={styles.selectBtnPlus}>
            <Icon
              color={EStyleSheet.value('$primaryBackgroundColor')}
              iconType="FontAwesome5"
              name="plus-circle"
              size={12}
            />
          </View>

          <Icon
            color={EStyleSheet.value('$primaryBlack')}
            iconType="MaterialCommunityIcons"
            name={iconName}
            size={24}
          />

          <Text style={styles.selectButtonLabel}>{text}</Text>
        </View>
      </TouchableOpacity>
    );
  };

  const _showFilesSource = HAS_FILES_SOURCE && mode === Modes.MODE_IMAGE;

  const _renderSelectButtons = (
    <>
      {_renderSelectButton(
        mode === Modes.MODE_VIDEO ? 'video-box' : 'image',
        'Gallery',
        handleOpenGallery,
      )}
      {_renderSelectButton('camera', 'Camera', handleOpenCamera)}
      {_showFilesSource && _renderSelectButton('folder', 'Files', handleOpenFiles)}
    </>
  );

  const _renderHeaderContent = () => (
    <View style={{ ...styles.buttonsContainer, paddingVertical: isExpandedMode ? 8 : 0 }}>
      <View
        style={[styles.selectButtonsContainer, _showFilesSource && styles.selectButtonsCompact]}
      >
        {mode === Modes.MODE_IMAGE
          ? _renderSelectButtons
          : isAddingToUploads
          ? _renderSelectButton('progress-upload', 'Uploading', handleOpenSpeakUploader)
          : _renderSelectButtons}
      </View>
      <View style={styles.pillBtnContainer}>
        <IconButton
          style={styles.uploadsActionBtn}
          color={EStyleSheet.value('$primaryBlack')}
          iconType="MaterialCommunityIcons"
          name="plus"
          size={28}
          onPress={() => {
            handleOpenGallery(true);
          }}
        />

        <IconButton
          style={{
            ...styles.uploadsActionBtn,
            backgroundColor: isDeleteMode ? EStyleSheet.value('$iconColor') : 'transparent',
          }}
          color={EStyleSheet.value('$primaryBlack')}
          iconType="MaterialCommunityIcons"
          name="minus"
          size={28}
          onPress={() => {
            setIsDeleteMode(!isDeleteMode);
            setDeleteIds([]);
          }}
        />
      </View>

      {isAddingToUploads && (
        <View style={styles.pillBtnContainer}>
          <ActivityIndicator color={EStyleSheet.value('$primaryBlue')} />
        </View>
      )}

      {isExpandedMode && _renderExpansionButton()}
    </View>
  );

  // render empty list placeholder
  const _renderEmptyContent = () => {
    return (
      <>
        <Text style={styles.emptyText}>
          {intl.formatMessage({ id: 'uploads_modal.label_no_images' })}
        </Text>
      </>
    );
  };

  const _renderExpansionButton = () => (
    <IconButton
      style={styles.pillBtnContainer}
      iconType="MaterialCommunityIcons"
      name={isExpandedMode ? 'arrow-collapse-vertical' : 'arrow-expand-vertical'}
      color={EStyleSheet.value('$primaryBlack')}
      size={32}
      onPress={() => {
        const _toValue = isExpandedMode ? COMPACT_HEIGHT : EXPANDED_HEIGHT;
        animatedHeight.value = withTiming(_toValue, {
          easing: Easing.inOut(Easing.cubic),
        });

        setIsExpandedMode(!isExpandedMode);
      }}
    />
  );

  const _renderDeleteButton = () => {
    if (deleteIds.length > 0) {
      const _delStyle = {
        ...styles.deleteButtonContainer,
        justifyContent: isExpandedMode ? 'flex-end' : 'center',
      } as ViewStyle;

      // Declarative enter/exit on this toggled button races Fabric view recycling on
      // Android (RetryableMountingLayerException "Unable to find viewState"). iOS only.
      return (
        <AnimatedView.View
          entering={Platform.OS === 'ios' ? SlideInRight : undefined}
          exiting={Platform.OS === 'ios' ? SlideOutRight : undefined}
          style={_delStyle}
        >
          <IconButton
            style={styles.deleteButton}
            color={EStyleSheet.value('$pureWhite')}
            iconType="MaterialCommunityIcons"
            name="delete-outline"
            disabled={isDeleting}
            size={28}
            onPress={_onDeletePress}
            isLoading={isDeleting}
          />
        </AnimatedView.View>
      );
    }
    return null;
  };

  const _visibleItemsChanged = (info: {
    viewableItems: ViewToken<MediaItem>[];
    changed: ViewToken<MediaItem>[];
  }) => {
    const visibleMap = {} as { [key: string]: boolean };

    info.viewableItems.forEach((viewable) => {
      if (viewable.index !== null) {
        visibleMap[viewable.index] = viewable.isViewable;
      }
    });
    setViewableItemsMap(visibleMap);
  };

  const _renderFooter = () => {
    if (!isExpandedMode && mediaUploads.length > 0) {
      return _renderExpansionButton();
    }
    if (isExpandedMode && isFetchingNextPage) {
      return (
        <View style={{ padding: 16, alignItems: 'center' }}>
          <ActivityIndicator color={EStyleSheet.value('$primaryBlue')} />
        </View>
      );
    }
    return null;
  };

  return (
    <Animated.View style={{ ...styles.container, height: animatedHeight }}>
      <FlatList
        key={isExpandedMode ? 'vertical_grid' : 'horizontal_list'}
        data={mediaUploads.slice(0, !isExpandedMode ? MAX_HORIZONTAL_THUMBS : undefined)}
        keyExtractor={(item) => `item_${item.url}`}
        renderItem={_renderItem}
        style={{ flex: 1 }}
        contentContainerStyle={
          isExpandedMode ? styles.gridContentContainer : styles.listContentContainer
        }
        ListHeaderComponent={_renderHeaderContent}
        ListEmptyComponent={_renderEmptyContent}
        ListFooterComponent={_renderFooter}
        extraData={deleteIds}
        horizontal={!isExpandedMode}
        numColumns={isExpandedMode ? 2 : 1}
        keyboardShouldPersistTaps="always"
        onViewableItemsChanged={_visibleItemsChanged}
        onScroll={_onScroll}
        onEndReached={_handleLoadMore}
        onEndReachedThreshold={0.5}
      />

      {_renderDeleteButton()}
    </Animated.View>
  );
};

export default UploadsGalleryContent;
