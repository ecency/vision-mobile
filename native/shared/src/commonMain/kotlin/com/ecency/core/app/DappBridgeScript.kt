package com.ecency.core.app

/**
 * The page side of the dApp bridge: RN's hiveExtensionBridge.ts (window.hive_keychain, the Keeper identity on
 * window.hive.providers) and peakVaultBridge.ts (window.peakvault), kept as RN wrote them, with one transport
 * change. Requests leave through the platform's message channel instead of window.ReactNativeWebView, and answers
 * come back through it ([receive] below), so no answer is ever evaluated as script in the page:
 * - Android: an androidx.webkit WebMessageListener object `ecencyBridge` (postMessage out, onmessage in), bound to
 *   https origins, answering the document that asked;
 * - iOS: `window.webkit.messageHandlers.ecencyBridge` out; the answer is passed as a JSON string to the receiver.
 *
 * The script defines nothing in frames (top window only), on non-https pages, or on hivesigner.com. Native code
 * checks the same again for every message (BrowserPolicy.bridgeOrigin), since a page can post to the channel
 * without this script. This is the dApp-facing API the bridge has to speak, not app logic: the app's logic stays
 * native (DappBrowserModel).
 */
object DappBridgeScript {
    enum class Platform { ANDROID, IOS }

    /** The receiver an iOS answer calls: `window.<RECEIVER>(json)`. */
    const val RECEIVER = "__ecencyBridgeReceive"

    /** The message channel's name on both platforms. */
    const val CHANNEL = "ecencyBridge"

    fun source(platform: Platform): String {
        val transport = when (platform) {
            Platform.ANDROID -> """
  var __channel = window.$CHANNEL;
  if (!__channel || typeof __channel.postMessage !== 'function') return;
  var __post = function(s) { __channel.postMessage(s); };
  __channel.onmessage = function(e) { __receive(e.data); };
"""
            Platform.IOS -> """
  var __handler = window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.$CHANNEL;
  if (!__handler) return;
  var __post = function(s) { __handler.postMessage(s); };
  Object.defineProperty(window, '$RECEIVER', { value: function(s) { __receive(s); }, writable: false, configurable: false });
"""
        }
        return BODY.replace("__TRANSPORT__", transport)
    }

    private val BODY = """
(function() {
  if (window.top !== window) return;
  if (window.location.protocol !== 'https:') return;
  if (/(^|\.)hivesigner\.com${'$'}/i.test(window.location.hostname)) return;
  if (window.hive_keychain || window.peakvault) return;
  var __receive = function(s) {
    var answer;
    try { answer = JSON.parse(s); } catch (e) { return; }
    if (!answer) return;
    if (answer.kind === 'keychain') hive_keychain.onAnswerReceived('hive_keychain_response', answer.response);
    else if (answer.kind === 'handshake') hive_keychain.onAnswerReceived('hive_keychain_handshake');
    else if (answer.kind === 'peakvault') {
      document.dispatchEvent(new CustomEvent('peak-vault-response-' + answer.id, { detail: JSON.stringify(answer.response) }));
    }
  };
__TRANSPORT__
  var hive_keychain = {
    current_id: 1,
    requests: {},
    handshake_callback: null,
    requestHandshake: function(callback) { this.handshake_callback = callback; this.dispatchCustomEvent('swHandshake_hive', {}); },
    requestEncodeMessage: function(username, receiver, message, key, callback, rpc) {
      this.dispatchCustomEvent('swRequest_hive', { type: 'encode', username: username, receiver: receiver, message: message, method: key, rpc: rpc }, callback);
    },
    requestVerifyKey: function(account, message, key, callback, rpc) {
      this.dispatchCustomEvent('swRequest_hive', { type: 'decode', username: account, message: message, method: key, rpc: rpc }, callback);
    },
    requestSignBuffer: function(account, message, key, callback, rpc, title) {
      this.dispatchCustomEvent('swRequest_hive', { type: 'signBuffer', username: account, message: message, method: key, rpc: rpc, title: title }, callback);
    },
    requestAddAccountAuthority: function(account, authorizedUsername, role, weight, callback, rpc) {
      this.dispatchCustomEvent('swRequest_hive', { type: 'addAccountAuthority', username: account, authorizedUsername: authorizedUsername, role: role, weight: weight, method: 'Active', rpc: rpc }, callback);
    },
    requestRemoveAccountAuthority: function(account, authorizedUsername, role, callback, rpc) {
      this.dispatchCustomEvent('swRequest_hive', { type: 'removeAccountAuthority', username: account, authorizedUsername: authorizedUsername, role: role, method: 'Active', rpc: rpc }, callback);
    },
    requestAddKeyAuthority: function(account, authorizedKey, role, weight, callback, rpc) {
      this.dispatchCustomEvent('swRequest_hive', { type: 'addKeyAuthority', username: account, authorizedKey: authorizedKey, weight: weight, role: role, method: 'Active', rpc: rpc }, callback);
    },
    requestRemoveKeyAuthority: function(account, authorizedKey, role, callback, rpc) {
      this.dispatchCustomEvent('swRequest_hive', { type: 'removeKeyAuthority', username: account, authorizedKey: authorizedKey, role: role, method: 'Active', rpc: rpc }, callback);
    },
    requestBroadcast: function(account, operations, key, callback, rpc) {
      this.dispatchCustomEvent('swRequest_hive', { type: 'broadcast', username: account, operations: operations, method: key, rpc: rpc }, callback);
    },
    requestSignTx: function(account, tx, key, callback, rpc) {
      this.dispatchCustomEvent('swRequest_hive', { type: 'signTx', username: account, tx: tx, method: key, rpc: rpc }, callback);
    },
    requestSignedCall: function(account, method, params, key, callback, rpc) {
      this.dispatchCustomEvent('swRequest_hive', { type: 'signedCall', username: account, method: method, params: params, typeWif: key, rpc: rpc }, callback);
    },
    requestPost: function(account, title, body, parent_perm, parent_account, json_metadata, permlink, comment_options, callback, rpc) {
      this.dispatchCustomEvent('swRequest_hive', { type: 'post', username: account, title: title, body: body, parent_perm: parent_perm, parent_username: parent_account, json_metadata: json_metadata, permlink: permlink, comment_options: comment_options, rpc: rpc }, callback);
    },
    requestVote: function(account, permlink, author, weight, callback, rpc) {
      this.dispatchCustomEvent('swRequest_hive', { type: 'vote', username: account, permlink: permlink, author: author, weight: weight, rpc: rpc }, callback);
    },
    requestCustomJson: function(account, id, key, json, display_msg, callback, rpc) {
      this.dispatchCustomEvent('swRequest_hive', { type: 'custom', username: account, id: id, method: key, json: json, display_msg: display_msg, rpc: rpc }, callback);
    },
    requestTransfer: function(account, to, amount, memo, currency, callback, enforce, rpc) {
      this.dispatchCustomEvent('swRequest_hive', { type: 'transfer', username: account, to: to, amount: amount, memo: memo, enforce: enforce || false, currency: currency, rpc: rpc }, callback);
    },
    requestSendToken: function(account, to, amount, memo, currency, callback, rpc) {
      this.dispatchCustomEvent('swRequest_hive', { type: 'sendToken', username: account, to: to, amount: amount, memo: memo, currency: currency, rpc: rpc }, callback);
    },
    requestDelegation: function(username, delegatee, amount, unit, callback, rpc) {
      this.dispatchCustomEvent('swRequest_hive', { type: 'delegation', username: username, delegatee: delegatee, amount: amount, unit: unit, rpc: rpc }, callback);
    },
    requestWitnessVote: function(username, witness, vote, callback, rpc) {
      this.dispatchCustomEvent('swRequest_hive', { type: 'witnessVote', username: username, witness: witness, vote: vote, rpc: rpc }, callback);
    },
    requestProxy: function(username, proxy, callback, rpc) {
      this.dispatchCustomEvent('swRequest_hive', { type: 'proxy', username: username, proxy: proxy, rpc: rpc }, callback);
    },
    requestPowerUp: function(username, recipient, hive, callback, rpc) {
      this.dispatchCustomEvent('swRequest_hive', { type: 'powerUp', username: username, recipient: recipient, steem: hive, rpc: rpc }, callback);
    },
    requestPowerDown: function(username, hive_power, callback, rpc) {
      this.dispatchCustomEvent('swRequest_hive', { type: 'powerDown', username: username, steem_power: hive_power, rpc: rpc }, callback);
    },
    requestCreateClaimedAccount: function(username, new_account, owner, active, posting, memo, callback, rpc) {
      this.dispatchCustomEvent('swRequest_hive', { type: 'createClaimedAccount', username: username, new_account: new_account, owner: owner, active: active, posting: posting, memo: memo, rpc: rpc }, callback);
    },
    requestCreateProposal: function(username, receiver, subject, permlink, daily_pay, start, end, extensions, callback, rpc) {
      this.dispatchCustomEvent('swRequest_hive', { type: 'createProposal', username: username, receiver: receiver, subject: subject, permlink: permlink, start: start, end: end, daily_pay: daily_pay, extensions: extensions, rpc: rpc }, callback);
    },
    requestRemoveProposal: function(username, proposal_ids, extensions, callback, rpc) {
      this.dispatchCustomEvent('swRequest_hive', { type: 'removeProposal', username: username, proposal_ids: proposal_ids, extensions: extensions, rpc: rpc }, callback);
    },
    requestUpdateProposalVote: function(username, proposal_ids, approve, extensions, callback, rpc) {
      this.dispatchCustomEvent('swRequest_hive', { type: 'updateProposalVote', username: username, proposal_ids: proposal_ids, approve: approve, extensions: extensions, rpc: rpc }, callback);
    },
    requestConversion: function(username, amount, collaterized, callback, rpc) {
      this.dispatchCustomEvent('swRequest_hive', { type: 'convert', username: username, amount: amount, collaterized: collaterized, rpc: rpc }, callback);
    },
    requestSavingsOperation: function(username, to, amount, currency, operation, memo, callback, rpc) {
      if (typeof memo === 'function') { rpc = callback; callback = memo; memo = ''; }
      this.dispatchCustomEvent('swRequest_hive', { type: 'savings', username: username, to: to, amount: amount, currency: currency, operation: operation, memo: memo || '', rpc: rpc }, callback);
    },
    requestRecurrentTransfer: function(username, to, amount, currency, memo, recurrence, executions, callback, rpc, pair_id) {
      this.dispatchCustomEvent('swRequest_hive', { type: 'recurrentTransfer', username: username, to: to, amount: amount, currency: currency, memo: memo, recurrence: recurrence, executions: executions, rpc: rpc, pair_id: pair_id }, callback);
    },
    requestSwap: function(username, startToken, endToken, amount, slippage, steps, callback, rpc, partnerUsername, partnerFee) {
      this.dispatchCustomEvent('swRequest_hive', { type: 'swap', username: username, startToken: startToken, endToken: endToken, amount: amount, slippage: slippage, steps: steps, partnerUsername: partnerUsername, partnerFee: partnerFee, rpc: rpc }, callback);
    },
    requestAddAccount: function(username, keys, callback) {
      this.dispatchCustomEvent('swRequest_hive', { type: 'addAccount', username: username, keys: keys }, callback);
    },
    dispatchCustomEvent: function(name, data, callback) {
      data.domain = window.location.href;
      data.extension_id = 'keeper';
      if (typeof callback === 'function') this.requests[this.current_id] = callback;
      try {
        __post(JSON.stringify({ name: name, request_id: this.current_id, data: data }));
      } catch (e) {
        if (typeof callback === 'function') {
          callback({ success: false, error: 'Ecency bridge not available' });
          delete this.requests[this.current_id];
        }
      }
      this.current_id++;
    },
    onAnswerReceived: function(type, response) {
      if (type && type === 'hive_keychain_response') {
        if (response && response.request_id) {
          var cb = this.requests[response.request_id];
          if (typeof cb === 'function') cb(response);
          delete this.requests[response.request_id];
        }
      } else if (type && type === 'hive_keychain_handshake') {
        if (typeof this.handshake_callback === 'function') {
          this.handshake_callback();
          this.handshake_callback = null;
        }
      }
    }
  };
  window.hive_keychain = hive_keychain;
  window.hive_keychain_extension = true;
  hive_keychain.isKeeper = true;
  if (!window.hive) window.hive = hive_keychain;
  if (!window.hive.providers) window.hive.providers = [];
  window.hive.providers.push({ name: 'Hive Keeper', rdns: 'com.ecency.keeper', provider: hive_keychain });

  var PeakVault = (function() {
    function PeakVault() { this._responseId = 0; this._rpc = 'https://api.hive.blog'; }
    PeakVault.prototype.setRpc = function(rpc) { this._rpc = rpc; };
    PeakVault.prototype._trigger = function(data) {
      data.sourceUrl = window.location.toString();
      data.rpc = this._rpc;
      document.dispatchEvent(new CustomEvent('peak-vault-event', { detail: data }));
    };
    PeakVault.prototype._triggerWithResponse = function(data) {
      var resId = this._responseId;
      this._responseId++;
      data.resId = resId;
      return new Promise(function(resolve, reject) {
        var listener = function(event) {
          document.removeEventListener('peak-vault-response-' + resId, listener);
          try {
            var detail = JSON.parse(event.detail);
            if (detail.error) reject(detail); else resolve(detail);
          } catch (e) {
            reject({ error: 'Failed to parse response' });
          }
        };
        document.addEventListener('peak-vault-response-' + resId, listener);
        this._trigger(data);
      }.bind(this));
    };
    PeakVault.prototype.requestSignOp = function(account, operations, keyRole, displayMessage) {
      return this._triggerWithResponse({ id: 'sign-request', open: 'index.html', account: account, operations: operations, keyRole: keyRole, broadcast: false, displayMessage: displayMessage || { title: 'Sign transaction' } });
    };
    PeakVault.prototype.requestSignTx = function(account, transaction, keyRole) {
      return this._triggerWithResponse({ id: 'sign-request', open: 'index.html', account: account, operations: transaction.operations || [], keyRole: keyRole, broadcast: false, displayMessage: { title: 'Sign transaction' }, transaction: transaction });
    };
    PeakVault.prototype.requestBroadcast = function(account, operations, keyRole, displayMessage) {
      return this._triggerWithResponse({ id: 'sign-request', open: 'index.html', account: account, operations: operations, keyRole: keyRole, broadcast: true, displayMessage: displayMessage || { title: 'Broadcast transaction' } });
    };
    PeakVault.prototype.requestCustomJson = function(account, id, keyRole, json, displayMessage) {
      var jsonStr = typeof json === 'string' ? json : JSON.stringify(json);
      var operations = [['custom_json', { required_auths: keyRole === 'active' ? [account] : [], required_posting_auths: keyRole !== 'active' ? [account] : [], id: id, json: jsonStr }]];
      return this.requestBroadcast(account, operations, keyRole, displayMessage || { title: 'Sign custom transaction' });
    };
    PeakVault.prototype.requestTransfer = function(from, to, amount, currency, memo) {
      var transfer = ['transfer', { from: from, to: to, amount: amount.toFixed(3) + ' ' + currency, memo: memo || '' }];
      return this.requestBroadcast(from, [transfer], 'active', { title: 'Transfer ' + currency, message: 'Send ' + amount.toFixed(3) + ' ' + currency + ' to @' + to });
    };
    PeakVault.prototype.requestVote = function(voter, permlink, author, weight) {
      return this.requestBroadcast(voter, [['vote', { voter: voter, permlink: permlink, author: author, weight: weight }]], 'posting', { title: 'Vote' });
    };
    PeakVault.prototype.requestPost = function(account, title, body, parentPermlink, parentAccount, json_metadata, permlink, otherOptions) {
      var operations = [['comment', { parent_author: parentAccount, parent_permlink: parentPermlink, author: account, permlink: permlink, title: title, body: body, json_metadata: JSON.stringify(json_metadata) }]];
      if (otherOptions) {
        operations.push(['comment_options', { author: account, permlink: permlink, max_accepted_payout: otherOptions.max_accepted_payout, percent_hbd: otherOptions.percent_hbd, allow_votes: otherOptions.allow_votes, allow_curation_rewards: otherOptions.allow_curation_rewards, extensions: otherOptions.extensions }]);
      }
      return this.requestBroadcast(account, operations, 'posting', { title: 'Post' });
    };
    PeakVault.prototype.requestSignBuffer = function(account, keyRole, message, displayMessage) {
      return this.requestSignOp(account, [['peakvault-sign_buffer', { message: message }]], keyRole, displayMessage || { title: 'Sign message', message: 'The website wants to verify that you have the required authority on this account.' });
    };
    PeakVault.prototype.connect = function() {
      return this.requestSignOp('', [['peakvault-connect', {}]], 'posting', { title: 'Connect', message: 'Let the website know which accounts are present in the wallet.' });
    };
    PeakVault.prototype.requestDecode = function(account, secret, keyRole) {
      return this.requestSignOp(account, [['peakvault-decode', { secret: secret }]], keyRole || 'memo', { title: 'Decode message', message: 'Decode an encrypted message using your private key.' });
    };
    PeakVault.prototype.requestEncode = function(account, recipientAccount, message) {
      return this.requestSignOp(account, [['peakvault-encode', { recipient: recipientAccount, message: message }]], 'memo', { title: 'Encode message', message: 'Encrypt a message.' });
    };
    PeakVault.prototype.requestEncodeWithKeys = function(account, keyRole, publicKeys, message) {
      return this.requestSignOp(account, [['peakvault-encode_with_keys', { publicKeys: publicKeys, message: message }]], keyRole, { title: 'Encode message', message: 'Encrypt a message with specific keys.' });
    };
    PeakVault.prototype.requestAddAccountAuthority = function(account, accountToAuthorize, weight, keyRole) {
      return this.requestBroadcast(account, [['account_update', { action: 'addAccountAuthority', account: account, accountToAuthorize: accountToAuthorize, weight: weight, keyRole: keyRole }]], 'active', { title: 'Add account authority' });
    };
    PeakVault.prototype.requestRemoveAccountAuthority = function(account, accountToRemove, keyRole) {
      return this.requestBroadcast(account, [['account_update', { action: 'removeAccountAuthority', account: account, accountToRemove: accountToRemove, keyRole: keyRole }]], 'active', { title: 'Remove account authority' });
    };
    PeakVault.prototype.requestAddKeyAuthority = function(account, keyToAuthorize, weight, keyRole) {
      return this.requestBroadcast(account, [['account_update', { action: 'addKeyAuthority', account: account, keyToAuthorize: keyToAuthorize, weight: weight, keyRole: keyRole }]], 'active', { title: 'Add key authority' });
    };
    PeakVault.prototype.requestRemoveKeyAuthority = function(account, keyToRemove, keyRole) {
      return this.requestBroadcast(account, [['account_update', { action: 'removeKeyAuthority', account: account, keyToRemove: keyToRemove, keyRole: keyRole }]], 'active', { title: 'Remove key authority' });
    };
    PeakVault.prototype.requestWitnessVote = function(account, witness, approve) {
      return this.requestBroadcast(account, [['account_witness_vote', { account: account, witness: witness, approve: approve }]], 'active', { title: 'Witness Vote' });
    };
    PeakVault.prototype.requestGovernanceProxy = function(account, proxy) {
      return this.requestBroadcast(account, [['account_witness_proxy', { account: account, proxy: proxy }]], 'active', { title: 'Set Proxy' });
    };
    PeakVault.prototype.requestDelegation = function(delegator, delegatee, amount, unit) {
      return this.requestBroadcast(delegator, [['delegate_vesting_shares', { delegator: delegator, delegatee: delegatee, vesting_shares: amount + ' ' + (unit || 'VESTS') }]], 'active', { title: 'Delegation' });
    };
    PeakVault.prototype.requestStake = function(from, to, amount) {
      return this.requestBroadcast(from, [['transfer_to_vesting', { from: from, to: to || from, amount: amount.toFixed(3) + ' HIVE' }]], 'active', { title: 'Power Up' });
    };
    PeakVault.prototype.requestUnstake = function(account, amount) {
      return this.requestBroadcast(account, [['withdraw_vesting', { account: account, vesting_shares: amount + ' VESTS' }]], 'active', { title: 'Power Down' });
    };
    PeakVault.prototype.requestRecurrentTransfer = function(from, to, amount, currency, memo, recurrence, executions, extensions) {
      return this.requestBroadcast(from, [['recurrent_transfer', { from: from, to: to, amount: amount.toFixed(3) + ' ' + currency, memo: memo || '', recurrence: recurrence, executions: executions, extensions: extensions || [] }]], 'active', { title: 'Recurrent Transfer' });
    };
    PeakVault.prototype.requestConversion = function(account, amount, HBD2HIVE) {
      var op = [HBD2HIVE ? 'collateralized_convert' : 'convert', { owner: account, amount: amount.toFixed(3) + ' ' + (HBD2HIVE ? 'HIVE' : 'HBD'), requestid: Math.floor(Date.now() / 1000) }];
      return this.requestBroadcast(account, [op], 'active', { title: 'Convert' });
    };
    PeakVault.prototype.requestCreateProposal = function(account, fundsReceiver, dailyPay, title, permlink, start, end, extensions) {
      return this.requestBroadcast(account, [['create_proposal', { creator: account, receiver: fundsReceiver, start_date: start, end_date: end, daily_pay: dailyPay, subject: title, permlink: permlink, extensions: extensions || [] }]], 'active', { title: 'Create Proposal' });
    };
    PeakVault.prototype.requestRemoveProposal = function(creator, proposalIds, extensions) {
      return this.requestBroadcast(creator, [['remove_proposal', { proposal_owner: creator, proposal_ids: proposalIds, extensions: extensions || [] }]], 'active', { title: 'Remove Proposal' });
    };
    PeakVault.prototype.requestUpdateProposalVotes = function(voter, proposalIds, approve, extensions) {
      return this.requestBroadcast(voter, [['update_proposal_votes', { voter: voter, proposal_ids: proposalIds, approve: approve, extensions: extensions || [] }]], 'active', { title: 'Proposal Vote' });
    };
    PeakVault.prototype.requestCreateClaimedAccount = function(creator, newAccount, ownerAuthority, activeAuthority, postingAuthority, publicMemoKey) {
      return this.requestBroadcast(creator, [['create_claimed_account', { creator: creator, new_account_name: newAccount, owner: ownerAuthority, active: activeAuthority, posting: postingAuthority, memo_key: publicMemoKey, json_metadata: '', extensions: [] }]], 'active', { title: 'Create Account' });
    };
    PeakVault.prototype.requestContact = function() {
      return this.requestSignOp('', [['peakvault-choose_contact', {}]], 'posting', { title: 'Choose Contact' });
    };
    return PeakVault;
  })();
  var peakvault = new PeakVault();
  window.peakvault = peakvault;
  peakvault.isVault = true;
  if (!window.hive) window.hive = peakvault;
  if (!window.hive.providers) window.hive.providers = [];
  window.hive.providers.push({ name: 'Peak Vault', rdns: 'com.peakd.vault', provider: peakvault });
  document.addEventListener('peak-vault-event', function(event) {
    var detail = event.detail;
    if (!detail || !detail.id) return;
    try { __post(JSON.stringify({ name: 'pvRequest', data: detail })); } catch (e) {}
  });
})();
true;
"""
}
