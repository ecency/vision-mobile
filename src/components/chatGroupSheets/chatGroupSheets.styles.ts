import EStyleSheet from 'react-native-extended-stylesheet';

export default EStyleSheet.create({
  sheetContainer: {
    paddingHorizontal: 0,
    backgroundColor: '$primaryBackgroundColor',
  },
  container: {
    paddingHorizontal: 20,
    paddingVertical: 24,
    paddingBottom: 40,
  },
  title: {
    fontSize: 20,
    fontWeight: 'bold',
    color: '$primaryBlack',
    textAlign: 'center',
    marginBottom: 8,
  },
  description: {
    fontSize: 14,
    color: '$primaryDarkGray',
    textAlign: 'center',
    marginBottom: 16,
    lineHeight: 20,
  },
  input: {
    borderWidth: 1,
    borderColor: '$primaryLightGray',
    borderRadius: 8,
    paddingHorizontal: 14,
    paddingVertical: 12,
    fontSize: 15,
    color: '$primaryBlack',
    backgroundColor: '$primaryLightBackground',
  },
  chips: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 6,
    marginBottom: 12,
  },
  chip: {
    flexDirection: 'row',
    alignItems: 'center',
    borderWidth: 1,
    borderColor: '$primaryLightGray',
    borderRadius: 16,
    paddingVertical: 3,
    paddingLeft: 3,
    paddingRight: 10,
    backgroundColor: '$primaryLightBackground',
  },
  chipAvatar: {
    width: 22,
    height: 22,
    borderRadius: 11,
    marginRight: 6,
  },
  chipText: {
    fontSize: 13,
    color: '$primaryBlack',
  },
  chipRemove: {
    fontSize: 15,
    color: '$primaryDarkGray',
    marginLeft: 6,
  },
  results: {
    maxHeight: 240,
    marginTop: 8,
    borderWidth: 1,
    borderColor: '$primaryLightGray',
    borderRadius: 8,
  },
  resultsSpinner: {
    marginVertical: 12,
  },
  emptyText: {
    fontSize: 13,
    color: '$primaryDarkGray',
    padding: 12,
  },
  errorText: {
    fontSize: 13,
    color: '$primaryRed',
    marginTop: 10,
  },
  confirmButton: {
    marginTop: 16,
    marginBottom: 0,
  },
  cancelButton: {
    backgroundColor: 'transparent',
    marginTop: 8,
  },
  cancelButtonText: {
    color: '$primaryDarkGray',
  },
  tabs: {
    flexGrow: 0,
    marginBottom: 12,
  },
  tab: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 12,
    paddingVertical: 6,
    borderRadius: 16,
    marginRight: 8,
    borderWidth: 1,
    borderColor: '$primaryLightGray',
  },
  tabActive: {
    borderColor: '$primaryBlue',
    backgroundColor: '$primaryLightBackground',
  },
  tabEmoji: {
    fontSize: 16,
  },
  tabCount: {
    fontSize: 13,
    fontWeight: '600',
    color: '$primaryDarkGray',
    marginLeft: 6,
  },
  tabCountActive: {
    color: '$primaryBlue',
  },
  list: {
    maxHeight: 360,
  },
  personRow: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: 8,
    paddingHorizontal: 8,
  },
  personAvatar: {
    width: 32,
    height: 32,
    borderRadius: 16,
    marginRight: 10,
  },
  personAvatarEmpty: {
    backgroundColor: '$primaryLightGray',
  },
  personName: {
    flex: 1,
    fontSize: 15,
    color: '$primaryBlack',
  },
});
