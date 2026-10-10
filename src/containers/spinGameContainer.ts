import { useCallback, useEffect, useRef, useState } from 'react';
import { Alert } from 'react-native';
import { useIntl } from 'react-intl';
import get from 'lodash/get';
import { useQueryClient } from '@tanstack/react-query';
import { getGameStatusCheckQueryOptions, useGameClaim } from '@ecency/sdk';
import { captureException } from '../utils/sentryUtils';
import { useAuth } from '../hooks';

const RedeemContainer = ({ children }: any) => {
  const intl = useIntl();
  const queryClient = useQueryClient();
  const { username, code } = useAuth();

  const [score, setScore] = useState(0);
  const [nextDate, setNextDate] = useState<any>(null);
  const [gameRight, setGameRight] = useState(1);
  const [isLoading, setIsLoading] = useState(true);
  const [claimKey, setClaimKey] = useState('');

  const claimMutation = useGameClaim(username, code, 'spin', claimKey);
  const claimMutationRef = useRef(claimMutation);
  const pendingGameStatusRef = useRef<any>(null);
  const isClaimingRef = useRef(false);

  useEffect(() => {
    claimMutationRef.current = claimMutation;
  }, [claimMutation]);

  // The status says whether a spin is left and carries the key that claims it, so it
  // must never come from cache: within the default staleTime a spin that was just
  // used would still read as available, and the app would offer and claim it again.
  // Each request also gets a key of its own, so a check made after a claim is never
  // answered by one that was already in flight before it.
  const statusSeqRef = useRef(0);
  const _fetchGameStatus = useCallback(() => {
    const options = getGameStatusCheckQueryOptions(username, code, 'spin');
    statusSeqRef.current += 1;
    return queryClient.fetchQuery({
      ...options,
      queryKey: [
        ...options.queryKey,
        'uncached',
        statusSeqRef.current,
      ] as unknown as typeof options.queryKey,
      staleTime: 0,
      gcTime: 0,
    });
  }, [code, queryClient, username]);

  const _statusCheck = useCallback(async () => {
    try {
      const request = _fetchGameStatus();
      const seq = statusSeqRef.current;
      const res = await request;
      // Only the newest answer sets the counters: an older request coming back late
      // would put a spin that has since been used back on screen.
      if (seq === statusSeqRef.current) {
        setGameRight(get(res, 'remaining', 0));
        setNextDate(get(res, 'next_date', null));
      }
      setIsLoading(false);
      return res;
    } catch (err) {
      if (err) {
        captureException(err, (scope) => scope.setTag('context', 'spin-game-status'));
        Alert.alert(get(err, 'message') || intl.formatMessage({ id: 'alert.unknow_error' }));
      }
      setIsLoading(false);
      return null;
    }
  }, [_fetchGameStatus]);

  const statusCheckRef = useRef(_statusCheck);

  useEffect(() => {
    statusCheckRef.current = _statusCheck;
    _statusCheck();
  }, [_statusCheck]);

  const _startGame = async (_type: any) => {
    // one claim at a time: a second press while one is in flight sends nothing
    if (isClaimingRef.current) {
      return;
    }
    isClaimingRef.current = true;

    let gameStatus = null;
    try {
      gameStatus = await _fetchGameStatus();
    } catch (err) {
      isClaimingRef.current = false;
      if (err) {
        captureException(err, (scope) => scope.setTag('context', 'spin-game-start'));
        Alert.alert(get(err, 'message') || intl.formatMessage({ id: 'alert.unknow_error' }));
      }
      return;
    }

    if (get(gameStatus, 'status') !== 18) {
      const key = get(gameStatus, 'key');
      if (!key) {
        isClaimingRef.current = false;
        Alert.alert('Game key missing');
        return;
      }
      pendingGameStatusRef.current = gameStatus;
      setClaimKey(key);
    } else {
      isClaimingRef.current = false;
      setNextDate(get(gameStatus, 'next_date'));
      setGameRight(0);
    }
  };

  useEffect(() => {
    if (!claimKey) {
      return;
    }

    const runClaim = async () => {
      try {
        const res = await claimMutationRef.current.mutateAsync();
        const gameStatus = pendingGameStatusRef.current;
        pendingGameStatusRef.current = null;

        setGameRight(get(gameStatus, 'status') !== 3 ? 0 : 5);
        setScore(get(res, 'score'));
        statusCheckRef.current();
      } catch (err) {
        pendingGameStatusRef.current = null;
        if (err) {
          captureException(err, (scope) => scope.setTag('context', 'spin-game-claim'));
          Alert.alert(get(err, 'message') || intl.formatMessage({ id: 'alert.unknow_error' }));
        }
      } finally {
        // The free spin key is the same every day, so clear it or the next spin on
        // this screen would not change the state and would never be sent.
        isClaimingRef.current = false;
        setClaimKey('');
      }
    };

    runClaim();
    // Keyed on the claim key alone. The status check is read through a ref because
    // its identity changes whenever the access token is refreshed, and re-running
    // this effect then would send the claim that was just made a second time.
  }, [claimKey]);

  return (
    children &&
    children({
      score,
      startGame: _startGame,
      gameRight,
      nextDate,
      isLoading,
      statusCheck: _statusCheck,
    })
  );
};

export default RedeemContainer;
