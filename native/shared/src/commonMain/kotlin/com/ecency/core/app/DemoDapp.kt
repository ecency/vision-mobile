package com.ecency.core.app

/**
 * A synthetic dApp page for debug runs and screenshots (never in a release build): the platforms load [HTML] as if
 * it came from [URL], so the bridge serves it like any https page. It asks for a vote when it loads and has buttons
 * for a transfer and a sign-in signature. Nothing it asks for reaches the chain in a fake-data run.
 */
object DemoDapp {
    const val URL = "https://dapp.example/"

    val HTML = """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Demo dApp</title><style>body{font-family:sans-serif;padding:24px}button{display:block;margin:12px 0;padding:12px;font-size:16px}</style></head>
<body><h2>Demo dApp</h2><p id="status">Asking for a vote...</p>
<button id="transfer" onclick="transfer()">Send 1 HIVE</button>
<button id="login" onclick="login()">Sign in with Keychain</button>
<script>
function show(r){document.getElementById('status').textContent=JSON.stringify(r);}
function transfer(){window.hive_keychain.requestTransfer('','spike-bob','1','Thanks','HIVE',show);}
function login(){window.hive_keychain.requestSignBuffer('','demo-login','Posting',show);}
setTimeout(function(){ if (window.hive_keychain) window.hive_keychain.requestVote('','native-golden-post','spike-author',10000,show); else show('no bridge'); }, 600);
</script></body></html>"""
}
