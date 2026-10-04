import { TextStyle, StyleSheet, ViewStyle, ImageStyle } from 'react-native';
import EStyleSheet from 'react-native-extended-stylesheet';
import getWindowDimensions from '../../../utils/getWindowDimensions';

export const THUMB_SIZE = 96;
export const GRID_THUMB_SIZE = getWindowDimensions().width / 2 - 32;
export const MAX_HORIZONTAL_THUMBS = 10;
export const COMPACT_HEIGHT = 112;
export const EXPANDED_HEIGHT = getWindowDimensions().height * 0.65;

export default EStyleSheet.create({
  container: {
    width: '100%',
    height: COMPACT_HEIGHT,
  },

  listContentContainer: {
    marginTop: 8,
    paddingRight: 72,
    paddingLeft: 16,
  },

  gridContentContainer: {
    width: '100%',
    paddingHorizontal: 16,
  },

  mediaItem: {
    marginLeft: 8,
    height: THUMB_SIZE,
    width: THUMB_SIZE,
    borderRadius: 16,
    backgroundColor: '$primaryLightBackground',
  } as ImageStyle,

  gridMediaItem: {
    marginHorizontal: 8,
    height: GRID_THUMB_SIZE,
    width: GRID_THUMB_SIZE,
    marginVertical: 8,
    borderRadius: 16,
    backgroundColor: '$primaryLightBackground',
  } as ImageStyle,

  inputContainer: {
    flex: 1,
  } as ViewStyle,
  titleInput: {
    color: '$primaryBlack',
    fontWeight: 'bold',
    fontSize: 18,
    textAlignVertical: 'top',
    paddingVertical: 0,
    backgroundColor: '$primaryBackgroundColor',
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: '$primaryDarkGray',
  } as TextStyle,

  title: {
    fontWeight: '700',
    flex: 1,
    fontSize: 16,
    color: '$primaryBlack',
  } as TextStyle,

  emptyText: {
    fontWeight: '700',
    flex: 1,
    fontSize: 16,
    color: '$primaryBlack',
    marginLeft: 12,
    alignSelf: 'center',
  },

  btnText: {
    color: '$pureWhite',
  } as TextStyle,
  saveButton: {
    backgroundColor: '$primaryBlue',
    width: 150,
    paddingVertical: 16,
    borderRadius: 32,
    justifyContent: 'center',
    alignItems: 'center',
  } as ViewStyle,

  closeButton: {
    marginRight: 16,
    paddingVertical: 8,
    borderRadius: 16,
    justifyContent: 'center',
    alignItems: 'center',
  } as ViewStyle,

  actionPanel: {
    flexDirection: 'row',
    justifyContent: 'flex-end',
    alignItems: 'center',
    marginBottom: 16,
  } as ViewStyle,

  itemIcon: {
    color: '$white',
  } as ViewStyle,

  buttonsContainer: {
    flexDirection: 'row',
    alignItems: 'center',
    marginRight: 2,
  } as ViewStyle,

  selectButtonsContainer: {
    justifyContent: 'space-around',
    paddingVertical: 8,
    marginRight: 8,
    height: THUMB_SIZE,
  } as ViewStyle,

  // three 32pt source buttons fill the thumbnail height exactly
  selectButtonsCompact: {
    paddingVertical: 0,
  } as ViewStyle,

  selectButton: {
    flexDirection: 'row',
    alignItems: 'center',
    height: 32,
  } as ViewStyle,

  selectBtnPlus: {
    marginRight: -8,
    marginTop: -16,
    zIndex: 2,
    borderRadius: 12,
    backgroundColor: '$primaryBlack',
  } as ViewStyle,

  selectButtonLabel: {
    fontSize: 16,
    textAlignVertical: 'top',
    color: '$primaryBlack',
    marginLeft: 4,
  } as TextStyle,

  pillBtnContainer: {
    height: THUMB_SIZE,
    width: THUMB_SIZE / 1.8,
    backgroundColor: '$primaryLightBackground',
    marginLeft: 8,
    borderRadius: 20,
    alignItems: 'center',
    justifyContent: 'space-around',
  } as ViewStyle,

  uploadsActionBtn: {
    height: THUMB_SIZE / 1.8,
    width: THUMB_SIZE / 1.8,
    borderRadius: 0,
    borderBottomLeftRadius: 20,
    borderBottomRightRadius: 20,
  } as ViewStyle,

  deleteButtonContainer: {
    position: 'absolute',
    right: 0,
    top: 16,
    bottom: 16,
    justifyContent: 'center',
  },

  deleteButton: {
    height: THUMB_SIZE / 1.8,
    width: THUMB_SIZE / 1.8,
    borderRadius: 0,
    borderBottomLeftRadius: 20,
    borderTopLeftRadius: 20,
    backgroundColor: '$primaryRed',
  } as ViewStyle,

  itemIconWrapper: {
    justifyContent: 'center',
    alignItems: 'center',
    backgroundColor: '$primaryRed',
  } as ViewStyle,

  minusContainer: {
    position: 'absolute',
    top: 12,
    left: 16,
    backgroundColor: '$primaryRed',
    borderRadius: 16,
    padding: 2,
  } as ViewStyle,

  counterContainer: {
    position: 'absolute',
    top: 12,
    left: 16,
    backgroundColor: '$primaryLightBackground',
    borderRadius: 16,
    padding: 2,
    height: 24,
    width: 24,
    justifyContent: 'center',
    alignItems: 'center',
  } as ViewStyle,

  counterText: {
    color: '$primaryBlack',
    fontSize: 16,
  } as TextStyle,

  statusContainer: {
    backgroundColor: '$primaryBlue',
    position: 'absolute',
    bottom: 0,
    left: 8,
    right: 0,
    borderBottomLeftRadius: 16,
    borderBottomRightRadius: 16,
    padding: 2,
    justifyContent: 'center',
    alignItems: 'center',
  } as ViewStyle,

  statusText: {
    color: '$pureWhite',
    fontSize: 14,
  } as TextStyle,

  checkStyle: {
    backgroundColor: '$white',
  } as ViewStyle,

  listEmptyFooter: {
    height: 80,
  } as ViewStyle,

  thumbPlaceholder: {
    height: THUMB_SIZE,
    width: THUMB_SIZE,
    backgroundColor: '$primaryLightBackground',
    marginLeft: 8,
    borderRadius: 20,
    justifyContent: 'center',
    alignItems: 'center',
  } as ViewStyle,

  loadingContainer: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    justifyContent: 'center',
    alignItems: 'center',
  } as ViewStyle,

  gifBadge: {
    position: 'absolute',
    left: 10,
    bottom: 10,
    backgroundColor: 'rgba(0,0,0,0.6)',
    borderRadius: 4,
    paddingHorizontal: 4,
    paddingVertical: 2,
  } as ViewStyle,
  gifBadgeText: {
    color: '$pureWhite',
    fontSize: 12,
    fontWeight: 'bold',
  } as TextStyle,
});
