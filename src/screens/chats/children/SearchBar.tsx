import React from 'react';
import { View, TextInput, TouchableOpacity } from 'react-native';
import { useIntl } from 'react-intl';
import EStyleSheet from 'react-native-extended-stylesheet';
import { Icon } from '../../../components';
import { chatsStyles as styles } from '../styles/chats.styles';

interface SearchBarProps {
  searchQuery: string;
  onSearchChange: (text: string) => void;
  sortByName: boolean;
  onToggleSort: () => void;
  /** Opens the New group sheet. */
  onNewGroup?: () => void;
  searchError: string | null;
}

export const SearchBar: React.FC<SearchBarProps> = React.memo(
  ({
    searchQuery,
    onSearchChange,
    sortByName,
    onToggleSort,
    onNewGroup,
    searchError: _searchError,
  }) => {
    const intl = useIntl();

    return (
      <View style={styles.searchContainer}>
        <View style={styles.searchInputRow}>
          <View style={styles.searchInputWrapper}>
            <Icon
              name="magnify"
              iconType="MaterialCommunityIcons"
              size={20}
              color={EStyleSheet.value('$iconColor')}
              style={styles.searchIcon}
            />
            <TextInput
              style={styles.searchInput}
              placeholder={intl.formatMessage({
                id: 'chats.search_placeholder',
                defaultMessage: 'Search channels and users',
              })}
              placeholderTextColor="#788187"
              value={searchQuery}
              onChangeText={onSearchChange}
              autoCapitalize="none"
              autoCorrect={false}
            />
            {!!searchQuery && (
              <TouchableOpacity onPress={() => onSearchChange('')} style={styles.clearButton}>
                <Icon
                  name="close-circle"
                  iconType="MaterialCommunityIcons"
                  size={18}
                  color={EStyleSheet.value('$iconColor')}
                />
              </TouchableOpacity>
            )}
          </View>

          <TouchableOpacity
            onPress={onToggleSort}
            style={[styles.sortButton, sortByName && styles.sortButtonActive]}
          >
            <Icon
              name="sort-alphabetical-ascending"
              iconType="MaterialCommunityIcons"
              size={20}
              color={EStyleSheet.value(sortByName ? '$primaryBlue' : '$iconColor')}
            />
          </TouchableOpacity>

          {onNewGroup && (
            <TouchableOpacity
              onPress={onNewGroup}
              style={styles.sortButton}
              accessibilityRole="button"
              accessibilityLabel={intl.formatMessage({
                id: 'chats.new_group',
                defaultMessage: 'New group',
              })}
            >
              <Icon
                name="account-multiple-plus"
                iconType="MaterialCommunityIcons"
                size={20}
                color={EStyleSheet.value('$iconColor')}
              />
            </TouchableOpacity>
          )}
        </View>
      </View>
    );
  },
);

SearchBar.displayName = 'SearchBar';
