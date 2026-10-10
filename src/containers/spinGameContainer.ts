import { useCallback, useEffect, useRef, useState } from 'react';
import { Alert } from 'react-native';
import { useIntl } from 'react-intl';
import get from 'lodash/get';
import { useQueryClient } from '@tanstack/react-query';
import { getGameStatusCheckQueryOptions, useGameClaim } from '@ecency/sdk';
import { captureException } from '../utils/sentryUtils';
import { useAuth } from '../hooks';
import QUERIES from '../providers/queries/queryKeys';

// Numbers every spin status request. Module level, not per screen: the query client
// outlives the screen, so a reopened screen must not reuse the key of a request the
// previous one left in flight.
let statusRequestSeq = 0;

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
  const isMountedRef = useRef(true);

  useEffect(() => {
    isMountedRef.current = true;
    return () => {
      isMountedRef.current = false;
    };
  }, []);

  useEffect(() => {
    claimMutationRef.current = claimMutation;
  }, [claimMutation]);

  // The status says whether a spin is left and carries the key that claims it, so it
  // must never come from cache: within the default staleTime a spin that was just
  // used would still read as available, and the app would offer and claim it again.
  // Each request also gets a key of its own, so a check made after a claim is never
  // answered by one that was already in flight before it. The request and its number
  // come back together; a request is the newest while no later one has started.
  const _fetchGameStatus = useCallback(() => {
    const options = getGameStatusCheckQueryOptions(username, code, 'spin');
    statusRequestSeq += 1;
    const seq = statusRequestSeq;
    const request = queryClient.fetchQuery({
      ...options,
      queryKey: [
        ...options.queryKey,
        QUERIES.GAMES.STATUS_UNCACHED,
        seq,
      ] as unknown as typeof options.queryKey,
      staleTime: 0,
      gcTime: 0,
    });
    return { request, isNewest: () => seq === statusRequestSeq };
  }, [code, queryClient, username]);

  const _statusCheck = useCallback(async () => {
    const { request, isNewest } = _fetchGameStatus();
    try {
      const res = await request;
      // Only the newest answer sets the counters: an older request coming back late
      // would put a spin that has since been used back on screen.
      if (isNewest()) {
        setGameRight(get(res, 'remaining', 0));
        setNextDate(get(res, 'next_date', null));
      }
      setIsLoading(false);
      return res;
    } catch (err) {
      // a failure of a request that has been superseded says nothing about now
      if (err && isNewest()) {
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

    const { request, isNewest } = _fetchGameStatus();
    let gameStatus = null;
    try {
      gameStatus = await request;
    } catch (err) {
      isClaimingRef.current = false;
      if (err && isNewest()) {
        captureException(err, (scope) => scope.setTag('context', 'spin-game-start'));
        Alert.alert(get(err, 'message') || intl.formatMessage({ id: 'alert.unknow_error' }));
      }
      return;
    }

    // A newer status request started while this one was out, and it may have seen
    // this spin used. Its key must not reach the claim.
    if (!isNewest()) {
      isClaimingRef.current = false;
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

        // A claim can outlive the screen. A status check started from a closed
        // screen would supersede the one a reopened screen is waiting for.
        if (!isMountedRef.current) {
          return;
        }
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
