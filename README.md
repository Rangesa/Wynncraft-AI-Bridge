# Wynn AI Bridge 0.8.0 — Minecraft 26.2 / Fabric / MCP + Wynncraft Knowledge Index

Minecraft/Wynncraft の状態を **画像OCRではなくクライアント内部のテキストと状態から** AIへ渡すクライアントModです。Wynncraftの会話やtooltip、Ability Treeの表示を日本語化する機能も備えています。

v0.8.0 は、0.7.0のread/auth/network境界を保ち、1nodeずつのsemantic Ability選択、count-1 Bank deposit、Mod Menu設定画面を追加します。Refund/resetはread-only診断に留め、ゲーム側のconfirmation・cost・server挙動を実機確認できるまでwrite toolを公開しません。

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
  "version": "0.8.0",
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

`allowActions=false` の既定設定では、読み取り専用toolを19個公開します。既存15 toolsは名前・schema・挙動を維持し、公式Wynncraft read 3個と `wynn_get_skill_points` を追加します。

- Minecraft: `minecraft_get_state`, `minecraft_get_recent_text`, `minecraft_get_slot_tooltip`, `minecraft_get_context`, `minecraft_get_open_container`, `minecraft_get_inventory`, `minecraft_get_visible_ui`
- Wynncraft knowledge: `wynn_inspect_hovered_item`, `wynn_knowledge_search`, `wynn_search_items`, `wynn_get_item`, `wynn_search_locations`, `wynn_search_wiki`, `wynn_get_wiki_page`, `wynn_index_status`, `wynn_get_official_ability_tree`, `wynn_get_class_info`, `wynn_get_player_abilities`, `wynn_get_skill_points`

`allowActions=true` にすると、認証付きの `minecraft_send_command` と `minecraft_send_chat` も追加されます。

`allowUiActions=false` が既定値です。tools/listは既定20個（Minecraft client read 7個 + Wynncraft/semantic read 13個）で、Ability Tree runtime診断もread-onlyとして常に利用できます。tools/list生成ロジック上のtool数は次のとおりです。

| allowUiActions | allowActions | tools/list |
|---|---:|---:|
| false | false | 20 |
| true | false | 30 |
| false | true | 22 |
| true | true | 32 |

`allowActions`、Bearer token、command/chatの認証条件は変更しません。UI write toolはすべてlocal category armも必要です。

- `minecraft_click_gui_slot`: fresh readのscreen class / syncId / stateRevision / item名が一致した場合だけ、自分のバニラinventory画面にあるitem slotを1回クリック
- `minecraft_move_inventory_item`: 自分のmain inventory/hotbar間でstack全体を移動。移動先は空、または同じitem/componentsでstack全体が収まる場合だけ許可
- `minecraft_withdraw_bank_item`: 認識できた現在のBank pageのcontent slot 0–44から、count 1のアイテムを空のplayer inventory slotへ1個withdraw。page/screen/menu/syncId/revision/itemを実行直前に照合し、サーバー同期後に両slotを検証
- `wynn_deposit_bank_item`: 現在認識できるBank pageへplayer inventoryからcount 1のitemを1個deposit。空きBank slotはBridgeが選び、stack移動やshift-clickは行わない
- `wynn_open_bank_page`: 意味上のpage番号を受け取り、認識したBank navigation controlで隣の1ページだけ移動。遠いページは最新read/revisionを取り直して繰り返す
- `minecraft_equip_item`: `helmet` / `chestplate` / `leggings` / `boots` の空いているvanilla装備slotへ、Minecraftの`Equippable` componentが一致するitemを移動。装備済みitemの置換とWynncraft独自のアクセサリ/装備GUIは対象外
- `wynn_open_character_info`: インベントリ内で一意に認識したCharacter Info compassを使う。外部引数にslotはなく、現在選択中hotbarにitemがある場合だけ実行
- `wynn_assign_skill_points`: skill名とpoint数を指定。Character Info画面、残数、現在値、button tooltipを一意に認識できた場合だけ各pointを1回ずつクリックし、server sync後にskill増加と残数減少を検証
- `wynn_open_ability_tree`: Character Info内でitem name/tooltipから一意に見つかったAbility Tree controlだけをクリックし、画面遷移を確認
- `wynn_get_ability_tree`: 公式node/archetypeを、実機item identity、slot、tooltip、rendered evidenceと照合。各nodeにmatch confidenceを返し、曖昧なnodeは`UNKNOWN`のまま返す。refund/resetに関係する表示文言も推測なしでread-only取得
- `wynn_select_ability`: `abilityId`と`classId`で一つのnodeだけ選択。ability arm必須。official tree、class、page、revision、node item identity、AP、NODE/ARCHETYPE/locksをpreflightし、クリックは1回だけ。AP消費・SELECTED状態・GUI revisionをserver同期後に検証

任意のHandledScreenやpixel座標へのwrite APIは公開しません。Bank認識は`ContainerScreen` + `ChestMenu` + 90-slot構造、container/player slot ownership、`Quick Actions` / `Storage Type`、prev/next page control、captured page textを組み合わせます。Page 1、途中ページ、最終ページはそれぞれ実際に見えているnavigationから判定します。`pageCount`は明示テキストがある場合だけ返します。withdraw/depositはcount 1・1 slotだけで、shift-clickやbulk stackは対象外です。drop / throw / destroy / sell / buy / trade確定 / arbitrary inputは公開しません。

Character Infoの実機画面やAbility Tree item identityが未取得の場合、buttonやnodeが完全に一致する読み取り根拠を持たない操作は拒否します。`wynn_select_ability`は呼び出しごとに1nodeだけ処理します。refund/resetの操作方法・cost・confirmation・server挙動はread-only diagnosticで生の表示証拠を収集します。公式Wikiは、tree menuを閉じる前なら直近でunlockしたnodeをright-clickでundoでき、treeを閉じた後の編集/resetには3 Ability ShardsとReset your Tree画面が必要と説明しています（[Ability Tree](https://wynncraft.wiki.gg/wiki/Ability_Tree)）。ただし0.8.0では対象画面・button/node identity・server結果をこのクライアントで実機検証できていないため、refund/reset write toolは公開しません。

### 公式Ability Tree data

外部知識MCP [mpeciakk/wynncraft-mcp](https://github.com/mpeciakk/wynncraft-mcp) のAPI設計を参考にし、コード依存やコードコピーはしていません。Bridgeは認証不要のWynncraft public APIから次を読みます。

- `GET https://api.wynncraft.com/v3/ability/tree/{class}` — official tree, node ids/names, pages, slots, coordinates, requirements, links, locks, archetypes, icons
- `GET https://api.wynncraft.com/v3/classes/{class}` — class metadata
- `GET https://api.wynncraft.com/v3/player/{username}/characters/{characterUuid}/abilities` — public character's unlocked nodes

Static tree/class data is cached for 24 hours under `config/wynn-ai-bridge-cache/official-ability/`. If the API is unavailable, an existing cache can still be read and marked stale. API failure never blocks client-local Minecraft read tools. Player abilities responses use a short 30-second in-memory cache to avoid repeating the public request.

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
/wynnbridge actions arm inventory 10
/wynnbridge actions arm bank 7
/wynnbridge actions arm skills 5
/wynnbridge actions arm ability 3
/wynnbridge actions disarm
```

起動時は必ずdisarmedです。引数なしの`arm`はInventoryカテゴリを1 actionだけ許可します。カテゴリ上限はinventory 10、bank 7、skills 5、ability 3です。実行時は一致するカテゴリをarmし、requested skill point数はその数だけskills quotaを消費します。通常UI actionは最初のinteraction前にquotaを予約します。Ability selectionはpreflight拒否ではquotaを消費せず、クリック直前に1 node分を消費します。クリック後の結果不明でもquotaは戻りません。arm状態と件数はメモリ上だけに保持し、disarm、timeout、disconnect、world changeで消去します。操作は同時に1件、MCP action間は750ms以上、同じJSON-RPC request idは再利用できません。毎回client threadでscreen/syncId/revision/itemを再取得し、server同期後に期待状態を確認します。確認できない場合は`verified=false`を返し、成功扱いにしません。

公開MCPが認証なしでも、disarmed状態でのwrite callは `UI actions are not locally armed` で拒否します。`allowActions=true` とtokenが設定されている場合は、従来どおり `/mcp` 全体にBearer認証が必要です。

### Mod Menu settings

Mod Menu 20.0.2以降が別途インストールされていれば、Wynn AI Bridgeの設定画面にversion、Bridge状態、bind/port、MCP tool数、action settings、現在のarm category/残り件数、Wynncraft API cache診断が表示されます。diagnostic summaryのclipboardコピーと公式tree/class cache削除もできます。

設定画面は直接armしません。`allowUiActions`をfalseにするとlocal armとallowanceを即時消去します。最大arm秒数は30–300秒です。Mod Menu APIのcompile-only signatureはMod JARに含めません。

## ChatGPTへつなぐ: Tailscale Funnel

BridgeはIPv4 loopbackの `127.0.0.1` だけで待ち受けます。デフォルトportは `8765` で、Minecraft Fabric clientが起動している間だけMCP serviceが応答します。Tailscale Funnelには `/mcp` mountだけを作り、rootや他のpathは設定しません。

```text
ChatGPT
  -> https://<node>.<tailnet>.ts.net/mcp
  -> Tailscale Funnel (HTTPS 443, /mcp only)
  -> http://127.0.0.1:<BRIDGE_PORT>/mcp
```

現在のproduction path（2026-09-30時点）は次のとおりです。

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

現行のChatGPT接続URLは `https://wynn-bridge.tail0243b7.ts.net/mcp` です。relay、portproxy、Funnelは運用環境の設定であり、このModは変更しません。`172.18.96.1`などのhost-side IPは環境固有なのでソースに固定しません。

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

`build` は `check` を実行し、Ability TreeとUI action security regressionも含みます。Minecraft稼働中は使用中JARを置換せず、必要なら別名 `.jar.pending` として配置してください。

生成物:

```text
build/libs/wynn-ai-bridge-0.8.0.jar
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
