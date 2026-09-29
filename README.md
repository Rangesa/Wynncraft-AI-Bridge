# Wynn AI Bridge 0.6.1 — Minecraft 26.2 / Fabric / MCP + Wynncraft Knowledge Index

Minecraft/Wynncraft の状態を **画像OCRではなくクライアント内部のテキストと状態から** AIへ渡すクライアントModです。Wynncraftの会話やtooltip、Ability Treeの表示を日本語化する機能も備えています。

v0.6.1 は、v0.6.0の読み取り機能と限定UI操作を維持し、認識済みBank Page 2から空のplayer inventory slotへ単一アイテムをwithdrawする操作を追加した版です。Bank操作にはMinecraft内で件数を明示したlocal armが必要です。Wynncraft独自glyphや数値を保護し、通常のplayer chatは既定でAIへ渡しません。UI操作は既定で無効です。

これにより、AIは「今見えているアイテム」を読むだけでなく、**そのアイテムの公式データ、セット、場所、入手/使い道/強化/商人/クエスト情報を自分で索引・検索**できます。

## 対象

- Minecraft Java Edition **26.2**
- Fabric Loader **0.19.5**
- Fabric API **0.161.0+26.2**
- Fabric Loom **1.17.21**
- Java **25**



## ゲーム状態・会話の取得

実機ログで確認できたWynncraft固有の描画ノイズを整理するため、次を追加しました。

- WynncraftのPrivate Use Area / Variation Selector / Minecraft色コードなど、描画専用glyphを自然言語から除去
- `gui.component` / `gui.formatted` / `gui.text` の同一描画を、サニタイズ済み本文 + 座標単位で重複排除
- Fabric `CHAT` イベントで受けたplayer chatと一致するGUI描画行を、`includeChat=false` 時に除外
- `/v1/text` と `minecraft_get_recent_text` に `dialogues` を追加
  - `speaker`
  - `text`
  - `canContinue`
  - `firstSeen` / `lastSeen`
- `minecraft_get_visible_ui` / `/v1/visible-ui` にも `includeChat` を追加（既定false）
- 周辺`TextDisplay` / custom name / 視線先名も同じサニタイザを通す

実機で観測した例：

```text
RAW:   <PUA glyphs> to continue <PUA glyphs> Let's hope no more grooks waltz in here. <PUA glyphs> The Cook
CLEAN: to continue Let's hope no more grooks waltz in here. The Cook
```

MCPではさらに次のように構造化して返します。

```json
{
  "speaker": "The Cook",
  "text": "Let's hope no more grooks waltz in here.",
  "canContinue": true
}
```

player chatをAIへ渡したい場合だけ、該当tool/RESTに `includeChat=true` を明示してください。

## Wynncraft画面の日本語翻訳

DeepL API keyを設定すると、WynncraftのHUD会話、アイテムtooltip、Ability Treeの説明を画面上で日本語表示できます。翻訳はバックグラウンドで行い、結果は種類ごとのローカルcacheへ保存します。DeepLを設定していなくても、内蔵glossaryにある固定用語は置き換えられます。

- 数字、能力値、アイテム名、Ability名、Wynncraft独自glyphや書式を翻訳APIへ送る前に保護します。
- 翻訳は画面表示だけに適用します。MCPが取得するtooltipや描画テキストは元の内容へ戻して返します。
- API keyは環境変数 `DEEPL_AUTH_KEY` / `DEEPL_API_KEY`、または `config/wynn-ai-bridge.properties` の `deepl.authKey` で設定できます。
- 任意で `DEEPL_API_URL` または `deepl.endpoint` にDeepL endpointを指定できます。

```properties
deepl.authKey=<your-deepl-api-key>
```

## v0.5.0 の追加 — Wynncraft Knowledge Index

### ローカル索引

起動時に次のWynncraft公式APIを非同期で読み込み、`.minecraft/config/wynn-ai-bridge-cache/` にJSONキャッシュします。

- `GET https://api.wynncraft.com/v3/item/database?fullResult`
  - 全アイテム
- `GET https://api.wynncraft.com/v3/item/sets`
  - Item Set / set bonus / set parts
- `GET https://api.wynncraft.com/v3/map/locations/markers`
  - 公式map marker

起動中も1分ごとにfreshnessだけ確認し、既定では60分以上古くなった時だけ再取得します。Wynncraft API側が1時間キャッシュしているデータと同じ周期を既定値にしています。

通信に失敗しても、以前のローカルキャッシュがあればそのまま検索できます。

### 公式Wiki検索

Merchant inventory、個別Quest walkthrough、特殊なupgrade path、アイテムの用途など、公式Item/Map APIに入っていない情報は、`wynncraft.wiki.gg` のMediaWiki APIをオンデマンド検索します。

- Wiki全文を丸ごとクロールしません
- 検索queryと選択した記事だけ取得します
- outbound先は `api.wynncraft.com` と `wynncraft.wiki.gg` に固定しています

### Wynncraft knowledge MCP tools

- `wynn_inspect_hovered_item`
  - hover中/指定slotの**実物tooltip**
  - 公式Item API record
  - 公式Wiki検索候補
  - を一度に返す
- `wynn_knowledge_search`
  - item / item set / map marker / official wikiを横断検索
- `wynn_search_items`
- `wynn_get_item`
- `wynn_search_locations`
- `wynn_search_wiki`
- `wynn_get_wiki_page`
- `wynn_index_status`

例えば、アイテムへカーソルを置いて

```text
これ何？
これ強化できる？
これどこで使う？
```

と聞いた場合、MCP hostはまず `wynn_inspect_hovered_item` を使えます。

リングについて

```text
リング売ってる場所ある？
このリングより良いのある？
このリングを強化したい
```

なら、`minecraft_get_inventory` / `wynn_search_items` / `wynn_knowledge_search` / `wynn_search_wiki` を組み合わせて調べられます。

### Wynncraft knowledge REST endpoints

```text
GET /v1/wynn/index
GET /v1/wynn/items?q=Pyrexia
GET /v1/wynn/item?name=Pyrexia
GET /v1/wynn/locations?q=Detlas
GET /v1/wynn/knowledge?q=Accessory%20Merchant
GET /v1/wynn/wiki/search?q=Accessory%20Merchant
GET /v1/wynn/wiki/page?title=Accessory%20Merchant
```

## v0.4.0 の追加

- **現在開いているコンテナGUIを丸ごと取得**
  - チェスト
  - エンダーチェスト（開いている間）
  - シュルカーボックス
  - ホッパー/かまど/各種コンテナ系GUI
  - Wynncraftが通常のcontainer slotとして見せるGUI
- 各slotについて `menuSlot / containerSlot / PLAYER_INVENTORY or OPEN_CONTAINER / hovered / active` を返す
- 各アイテムの **tooltip / lore 全行を、実際にマウスを乗せなくても生成**
- プレイヤー自身の hotbar / inventory / equipment を直接取得
- `GuiGraphicsExtractor.item(...)` / `fakeItem(...)` を捕捉し、**通常containerではないカスタムGUIに描画されたItemStackも短時間キャッシュ**
  - 商人リスト
  - レシピ系表示
  - WynncraftのカスタムGUIでItemStackとして描画される要素
  - tooltip対象ItemStack
- 新しいMCP tools:
  - `minecraft_get_open_container`
  - `minecraft_get_inventory`
  - `minecraft_get_visible_ui`
- 新しいREST:
  - `GET /v1/container`
  - `GET /v1/inventory`
  - `GET /v1/visible-ui`
- `/health` に `renderedItems` capture statsを追加
- GUI item captureは毎frame発火するため、保持用`ItemStack.copy()`は最大4回/秒程度に抑制

### 「ユーザーが見れるもの」の境界

このModは**クライアントに実際に届いていて、ユーザーが画面上で表示・確認できる情報**を対象にします。

重要: **閉じているチェスト/エンダーチェストの中身をサーバーから勝手に読む機能ではありません。** エンダーチェスト等は、そのGUIを実際に開いてクライアントへ内容が同期されている間に `minecraft_get_open_container` で取得します。閉じた後の内容を「現在値」として推測しません。

純粋なテクスチャや3D形状そのものは構造化テキストではないため、この版では画像Visionの代替にはしていません。一方、GUI/HUD文字列、ItemStack、tooltip/lore、container slot、視線先、TextDisplay/custom nameは画像を使わず取得します。

## v0.3.0 の追加

- `POST http://127.0.0.1:8765/mcp`
- MCP **2026-07-28** の stateless protocol に対応
  - `server/discover`
  - `tools/list`
  - `tools/call`
  - `MCP-Protocol-Version` / `Mcp-Method` / `Mcp-Name` の整合性確認
  - `resultType`, `ttlMs`, `cacheScope`, server info metadata
- MCP **2025-11-25** の legacy initialize/session flow も互換用に対応
- JSON-only Streamable HTTP。SSE/server-initiated request は使いません
- MCP tools:
  - `minecraft_get_state`
  - `minecraft_get_recent_text`
  - `minecraft_get_slot_tooltip`
  - `minecraft_get_context`
  - `minecraft_send_command` (`allowActions=true` の時だけ)
  - `minecraft_send_chat` (`allowActions=true` の時だけ)
- MCP側では通常player chatを **デフォルト除外**。`includeChat=true` の時だけ含めます
- MCP JSON-RPC用のdependency-free JSON parserを追加

従来のv0.2で入れたCSRF/DNS rebinding対策、tooltip取得、差分text取得、nearby text entity取得も維持しています。

## 何ができるか

Web ChatGPT等のMCP hostから、例えば次のような質問をした時に、AI側が必要なtoolを呼んでMinecraftを直接確認できる構成を狙っています。

```text
今何すればいい？
今話してるNPCは何て言ってる？
右に出てるTracked Questを説明して
この弓と今装備中の弓どっちが良い？
なんで体力減ってる？
```

NPC会話やTracked Questは、スクリーンショットOCRではなく、Minecraftが描画する前のテキストを捕捉します。

## MCP endpoint

Listen先はIPv4 loopbackのみです。

```text
http://127.0.0.1:8765/mcp
```

`GET /health` でもMCP protocolを確認できます。

```json
{
  "ok": true,
  "name": "wynn-ai-bridge",
  "version": "0.6.1",
  "mcp": {
    "path": "/mcp",
    "protocols": ["2026-07-28", "2025-11-25"]
  }
}
```

### MCP Inspectorで試す

MCP InspectorのTransportを **Streamable HTTP** にして、次を指定します。

```text
http://127.0.0.1:8765/mcp
```

現在のMCP SDKは2026-07-28を自動probeし、必要ならlegacy initializeへfallbackできます。

## MCP tools

`allowActions=false` の既定設定では、読み取り専用toolを15個公開します。

- Minecraft: `minecraft_get_state`, `minecraft_get_recent_text`, `minecraft_get_slot_tooltip`, `minecraft_get_context`, `minecraft_get_open_container`, `minecraft_get_inventory`, `minecraft_get_visible_ui`
- Wynncraft knowledge: `wynn_inspect_hovered_item`, `wynn_knowledge_search`, `wynn_search_items`, `wynn_get_item`, `wynn_search_locations`, `wynn_search_wiki`, `wynn_get_wiki_page`, `wynn_index_status`

`allowActions=true` にすると、認証付きの `minecraft_send_command` と `minecraft_send_chat` も追加されます。

`allowUiActions=false` が既定値です。`allowUiActions=true` にすると、読み取り専用のruntime診断tool 1個と、別の安全ゲートを使うwrite tool 4個が追加され、tools/listは合計20個になります。`allowActions`、Bearer token、command/chatの認証条件は変更しません。

- `minecraft_click_gui_slot`: fresh readのscreen class / syncId / stateRevision / item名が一致した場合だけ、自分のバニラinventory画面にあるitem slotを1回クリック
- `minecraft_move_inventory_item`: 自分のmain inventory/hotbar間でstack全体を移動。移動先は空、または同じitem/componentsでstack全体が収まる場合だけ許可
- `minecraft_withdraw_bank_item`: 認識済みWynncraft Bank Page 2のcontent slot 0–44から、count 1のアイテムを空のplayer inventory slotへ1個withdraw。Page 2のscreen/menu/syncId/revisionとBankのページ操作ラベルを実行直前に照合し、サーバー同期後に両slotを検証。1回のlocal armで最大7回まで
- `minecraft_equip_item`: `helmet` / `chestplate` / `leggings` / `boots` の空いているvanilla装備slotへ、Minecraftの`Equippable` componentが一致するitemを移動。装備済みitemの置換とWynncraft独自のアクセサリ/装備GUIは対象外
- `wynn_get_ability_tree`: 現在のscreen、container、描画ItemStack、捕捉済みテキストを推測なしで返すruntime調査tool。Ability Treeのnode状態やpoint数は判別せず、クリックもしません

この実装は、任意のHandledScreenへの汎用write APIを公開しません。Bank withdrawは、`ContainerScreen` + `ChestMenu` + 90 slotsに加えて、`Quick Actions` / `Storage Type` / `Page 1 <<<<<` / `Page 3 >>>>>` が既知slotにあるPage 2だけを認識します。Bank content slot 0–44からcount 1の1アイテムを、空のplayer inventory slotへ標準pickup click 2回で移動し、Bankの他ページ、deposit、merchant、trade、bulk stack、shift-clickは拒否します。通常のslot clickとinventory moveは引き続き自分のvanilla inventoryに限定します。drop / throw / destroy / sell / buy / trade確定 / arbitrary inputは公開しません。

### `minecraft_get_context`

まずこれを使う想定です。現在状態＋直近の画面テキスト＋game/actionbar messageをまとめて返します。

入力例:

```json
{
  "maxAgeMs": 8000,
  "limit": 120,
  "includeChat": false
}
```

通常player chatはデフォルトでは送りません。

### `minecraft_get_recent_text`

WynncraftのNPC dialogue、Tracked Quest、GUI/HUD等のpre-render textを取得します。

```json
{
  "maxAgeMs": 10000,
  "source": "activeText.*",
  "limit": 100,
  "includeChat": false
}
```

### `minecraft_get_state`

- player位置/向き/HP
- 現在screen
- container GUIを開いている場合の非空slots
- hover slot
- crosshair target
- 周囲のcustomName/TextDisplay

を返します。

### `minecraft_get_slot_tooltip`

現在開いているcontainerのItemStack tooltip/loreを直接生成します。

```json
{"slot":17,"advanced":false}
```

`slot` を省略するとhover中slotです。

### `minecraft_get_open_container`

現在開いているcontainer GUIの全slotを取得します。チェスト/エンダーチェストを開いた状態なら、その中身とplayer inventory側を区別して返します。

```json
{
  "includeEmpty": false,
  "includeTooltips": true,
  "advanced": false
}
```

`includeTooltips=true` なら、各非空slotについてアイテム名だけでなくtooltip/lore全行を生成します。

### `minecraft_get_inventory`

プレイヤー自身のhotbar / main inventory / equipmentを取得します。GUIを開いていなくても取得できます。

```json
{
  "includeEmpty": false,
  "includeTooltips": true,
  "advanced": false
}
```

### `minecraft_get_visible_ui`

現在画面について最も広く見るtoolです。

- 直近のpre-render text
- GUIに実際に描画されたItemStack
- 現在開いているcontainerと全item lore
- state/crosshair/nearby text entity
- 任意でplayer inventory/equipment

```json
{
  "maxAgeMs": 1500,
  "textLimit": 400,
  "itemLimit": 300,
  "includeTooltips": true,
  "advanced": false,
  "includeInventory": false
}
```

通常は `minecraft_get_context` を先に使い、「GUIの中身を全部確認したい」「Wynncraftの画面で何が表示されているか細かく見たい」場合に `minecraft_get_visible_ui` を使う想定です。

### `minecraft_send_command` / `minecraft_send_chat`

ゲーム内状態を変更するため、`allowActions=true` のときだけ `tools/list` に出ます。Bearer tokenも必要です。

### 限定UI操作のlocal arm

MCPからarmするtoolはありません。UI操作を有効にするには、設定の `allowUiActions=true` に加え、Minecraft内で次の**client-only command**を実行します。これはserver commandとして送信されません。

```text
/wynnbridge actions arm
/wynnbridge actions arm bank 7
/wynnbridge actions disarm
```

起動時は必ずdisarmedです。通常の`arm`は最大 `uiActions.maxArmSeconds` 秒（既定300秒）UI操作を許可しますが、Bank withdraw allowanceは0です。Bankからの取り出しを使う場合だけ、Minecraft内で`arm bank <1-7>`を実行して許可件数を明示します。各成功または試行で1枠を消費し、最大7個まで、1 actionにつきcount 1のアイテム1個です。この枠はメモリ上だけに保持し、disarm、timeout、disconnect、world changeで消去します。操作は1件ずつ処理し、各actionの開始間隔は750ms以上、同じJSON-RPC request idは再利用できません。操作前に画面・syncId・revision・slot内容を再取得し、サーバー同期後に両slotを確認します。確認できない場合は`verified=false`を返し、成功扱いにしません。

公開MCPが認証なしでも、disarmed状態でのwrite callは `UI actions are not locally armed` で拒否します。`allowActions=true` とtokenが設定されている場合は、従来どおり `/mcp` 全体にBearer認証が必要です。

## ChatGPTへつなぐ: Tailscale Funnel

BridgeはIPv4 loopbackの `127.0.0.1` だけで待ち受けます。デフォルトportは `8765` で、Minecraft Fabric clientが起動している間だけMCP serviceが応答します。Tailscale Funnelには `/mcp` mountだけを作り、rootや他のpathは設定しません。

```text
ChatGPT
  -> https://<node>.<tailnet>.ts.net/mcp
  -> Tailscale Funnel (HTTPS 443, /mcp only)
  -> http://127.0.0.1:<BRIDGE_PORT>/mcp
```

現在のproduction path（2026-09-29時点）は次のとおりです。

```text
ChatGPT
  -> https://wynn-bridge.tail0243b7.ts.net/mcp
  -> Tailscale Funnel on WSL (/mcp)
  -> WSL 127.0.0.1:18766
  -> Windows 172.18.96.1:18765
  -> Windows portproxy
  -> Wynn AI Bridge 127.0.0.1:8765
```

`172.18.96.1` とrelay portはこのPCのWSL/Windowsネットワークに固有で、環境により変わります。Bridge sourceへ固定値を入れず、既存のrelay・portproxy・Firewall・Funnel設定を変更しないでください。

Tailscaleの `--set-path=/mcp` はmount prefixをproxy時に取り除くため、targetにも `/mcp` を付けます。これで公開URLの `/mcp` がBridgeの `/mcp` に届きます。`/`、`/health`、`/test`、`/v1/*` はmountされず、Bridgeへ転送されません。Bridge自体もLAN/WAN interfaceにはbindしません。Tailscaleは公開URLのHost headerをoriginへ渡すため、セットアップ後にゲームの `wynn-ai-bridge.properties` へ `tailscaleHost=<node>.<tailnet>.ts.net` を設定します。8443または10000を使う場合は `:<port>` も含めます。

### Direct Windows Funnelのセットアップ例

以下はWindowsでBridgeへ直接forwardする構成例です。上記の現行WSL production pathには適用しません。

1. Tailscale Windows clientを起動し、ChromeのTailscale管理画面と同じ、使用するtailnetのアカウントにサインインします。Windows serviceはPC起動時に自動起動します。
2. [Tailscale Admin Console](https://login.tailscale.com/admin) のDNS設定でMagicDNSとHTTPS certificatesが有効であることを確認します。Funnelの初回有効化では、TailscaleがHTTPS certificateを用意しtailnet policyへFunnel node attributeを追加します。既定の対象は `autogroup:member`（tailnet内の全member）なので、policyの対象範囲を確認してから承認してください。
3. Funnel設定を確認し、実行する場合は `-Apply` を付けます。Bridge portはゲーム設定の `port=` が基準です。既定portは `8765` で、変更している場合は同じ値を `-BridgePort` または `WYNN_AI_BRIDGE_PORT` でも指定します。

```powershell
# 設定内容を表示するだけ
.\scripts\setup-tailscale-funnel.ps1

# Admin Consoleで確認した正確なnode名を指定して公開
.\scripts\setup-tailscale-funnel.ps1 -Apply -ExpectedNodeDnsName '<node>.<tailnet>.ts.net'
```

スクリプトは既存のFunnel/Serve設定を検出すると処理を中断し、他のpathを上書きしません。適用後は次で状態を確認します。

```powershell
tailscale status
tailscale funnel status
tailscale funnel status --json
```

`--bg`設定はTailscale daemonに保存され、PC再起動やTailscale service再起動後もFunnelを再開します。BridgeはMinecraft内で動くため、endpointを使う間はMinecraft clientも起動しておく必要があります。

### 公開範囲とChatGPT URL

公開するのはTailscaleのHTTPS `443` 上の `/mcp` routeだけです。Funnelのcatch-allは作らず、未一致routeはTailscaleから404を返します。FunnelのHTTPS証明書は公開Certificate Transparency logにDNS名が記録されるため、node名に個人情報を含めないでください。

Bridgeの既定設定は `allowActions=false`、`allowUiActions=false` です。read-only MCP接続ではtoken不要です。`allowActions=true` にすると `/mcp` にもBearer tokenが必要になります。既存の認証なしconnectorではcommand/chat actionsを有効にしないでください。限定UI操作は別のlocal arm gateで制御されます。

ChatGPTに登録するURLはTailscaleから表示される次のURLです。

```text
https://<node>.<tailnet>.ts.net/mcp
```

## Actions と認証

設定ファイル:

```text
.minecraft/config/wynn-ai-bridge.properties
```

初期値:

```properties
port=8765
tailscaleHost=
allowActions=false
token=
allowUiActions=false
uiActions.maxArmSeconds=300
knowledge.enabled=true
knowledge.wikiEnabled=true
knowledge.refreshMinutes=60
knowledge.httpTimeoutSeconds=12
```

`allowActions=true` かつ `token=` が空なら、次回起動時に32-byteランダムtokenを生成して保存します。

```properties
allowActions=true
token=<auto-generated-secret>
```

tokenが設定されている場合、RESTだけでなく `/mcp` にも以下が必要です。

```text
Authorization: Bearer <token>
```

`uiActions.armed` のような永続設定はありません。arm状態はMinecraft process内だけに保持され、プロパティファイルへ保存されません。

## Browser / CSRF / DNS rebinding対策

- IPv4 `127.0.0.1` のみにbind
- `Host` はloopback host、または設定した `tailscaleHost` のみ許可
- `Origin` header付きrequestは拒否
- CORS headerは付与しない
- bearer tokenは `MessageDigest.isEqual` で比較
- action REST bodyは8KiB制限
- MCP bodyは64KiB制限

## REST API

MCPを使わないローカルagent用に従来APIも残しています。

```text
GET  /health
GET  /v1/state
GET  /v1/text?since=...&source=...&kind=...&limit=...
GET  /v1/container?includeTooltips=true&includeEmpty=false
GET  /v1/inventory?includeTooltips=true&includeEmpty=false
GET  /v1/visible-ui?maxAgeMs=1500&includeTooltips=true&includeChat=false
GET  /v1/slot/{n}/tooltip
GET  /v1/slot/hovered/tooltip
GET  /v1/wynn/index
GET  /v1/wynn/items?q=...
GET  /v1/wynn/item?name=...
GET  /v1/wynn/locations?q=...
GET  /v1/wynn/knowledge?q=...
GET  /v1/wynn/wiki/search?q=...
GET  /v1/wynn/wiki/page?title=...
POST /v1/action/command
POST /v1/action/chat
```

## `/health`

`require=0` のMixinがmapping差分で無言死していないか確認する診断です。

`captureSources` にsource別の総capture数・lastSeenが出ます。
`/health` はローカル診断用です。Tailscale Funnelのセットアップ例は `/mcp` だけを公開します。

## 色 / Wynncraft glyph

`getString()`だけではstyleを失うため、可能な箇所に `color` / `nameColor` を残しています。ただし複数色を持つ1行の完全なstyle treeではありません。

Wynncraft独自glyphだけで意味を持つ箇所はPrivate Use Area文字のまま残る可能性があります。将来的にはstyle-run/raw codepointとWynntils互換decoderを追加する想定です。

## Privacy

Minecraft chatには他プレイヤー名、party/guild/private message等が含まれる場合があります。

そのためMCPの `minecraft_get_context` / `minecraft_get_recent_text` / `minecraft_get_visible_ui` と、対応するREST APIは、通常chatを **デフォルトで除外**します。必要な時だけ `includeChat=true` にしてください。

クラウドLLMへ接続する以上、toolで取得した内容はそのLLMサービスへ送信され得ます。DM等を扱う場合は特に注意してください。

## ビルド

Minecraft 26.2の開発環境はJava 25です。

```powershell
gradle build
```

`build` は `check` を実行し、既存Ability Tree regressionとUI action security regressionも含みます。生成されたJARを使用するFabric 26.2 profileの `mods` フォルダへ配置してください。

生成物:

```text
build/libs/wynn-ai-bridge-0.6.1.jar
```

JARのversionは `gradle.properties` の `mod_version` から決まります。

## 簡易MCPテスト

Minecraft起動後、PowerShellからmodern discoveryを確認できます。

よく使うread-only MCP callsをまとめて確認するスクリプトもあります。

```powershell
.\tools\test-mcp.ps1
```

```powershell
$body = @{
  jsonrpc = "2.0"
  id = 1
  method = "server/discover"
  params = @{
    _meta = @{
      "io.modelcontextprotocol/protocolVersion" = "2026-07-28"
    }
  }
} | ConvertTo-Json -Depth 10

Invoke-RestMethod `
  -Uri "http://127.0.0.1:8765/mcp" `
  -Method Post `
  -Headers @{
    "MCP-Protocol-Version" = "2026-07-28"
    "Mcp-Method" = "server/discover"
  } `
  -ContentType "application/json" `
  -Body $body
```

## 実装上の注意

- HTTP threadからMinecraft stateを変更せず、tooltip/actionはclient threadへqueue
- textはsource + position + color + textで重複排除
- GUI ItemStackはsource + position + item identityで短時間重複排除し、copy頻度を抑制
- container/inventoryの全tooltip生成は常時tickではなくMCP/REST要求時だけ実行
- MCP tool resultは短い`content`とmachine-readable `structuredContent`を返す
- 2026-07-28ではlist responseに`ttlMs/cacheScope/resultType`を付加
- legacy 2025-11-25向けにinitialize/Mcp-Session-Idも受ける
- SSE, sampling, elicitation, resources, promptsは未実装
