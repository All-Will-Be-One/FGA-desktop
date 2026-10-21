# External execution boundary audit

Official upstream: `cfb0593c64aaaceeab3ba56040fa34ab8e32f7ba`. Audited fork baseline: `1fb4a2ebf43f8284782af7398c86b2cb6a39d223` plus its three preserved card-test working files. This audit covered the entire53-file fork difference (34 additions/19 modified upstream files), not only the most recent failure.

The count is **24 semantic rule families**, not24 Boolean conditions or throw sites. Repeated references count once. The following records pre-deletion locations and dispositions; line numbers refer to the audit snapshot, not the shortened files. All24 were fork additions, not requirements of upstream FGA. Private source snapshots and live logs are retained outside the repository.

| ID | 修改前路径/方法/行 | 触发/后果 | 删除/保留理由 |
|---|---|---|---|
| D1 | ExternalPlanProtocol.publish:83 | Cards没有beforeAttackFrame便拒绝发布 | 观测质量由宿主消费；帧元数据可缺失但如有仍报告，不在设备断言 |
| D2 | ExternalPlanProtocol.validateHandEvidence:126–138；submit:154/executing:173 | 要求固定host reader、完整未选手牌、SHA等证明 | 删除此1族2调用，不接管host识别质量；保留plan observation身份 |
| D3 | ExternalPlanProtocol.publish:86、submit:152、executing:169；ExternalPlanReceiver.await:32 | 120秒过期即中止/拒绝动作 | 删除这一独立观测时钟；仅保留显式整任务限时与取消 |
| D4 | BattleRunRequest.validate:80–81 | 因宿主reader不支持KR而禁止Android Smart | 设备不替宿主判断识别能力；删地区门槛，原生资源不变 |
| D5 | BattleRunRequest.validate:112–115 | Smart仍解析不会执行的legacy skillCommand | Smart不执行legacy脚本；只在普通script模式解析 |
| D6 | BattleRunRequest.validate:117–118 | Smart因unused旧脚本含令咒被拒绝 | Smart不加载执行脚本；script模式禁令咒仍属资源授权范围 |
| D7 | BattleRunRequest.validate:109–110 | 全局Boost未Disabled即拒绝 | 单run冻结profile已Disabled；删除冗余入口拒绝，不启用道具 |
| D8 | BattleRunRequest.validate:111 | 全局自动撤退开启即拒绝 | 单run冻结profile已有false；删除全局入口拒绝，不启用撤退 |
| D9 | AutomationBridge.guardInput:231–247；AccessibilityGestures.performGesture:157 | 每次gesture比较全局脚本、服务器、UI、资源、所有限制是否一致 | run已用独立snapshot；删除重复全局配置门槛及手势调用 |
| D10 | ScriptManager.runEntryPoint:782 | 可选录屏失败仅API运行升级fatal | 恢复上游日志提示继续；屏幕捕获权限失效仍真实报错 |
| D11 | ScriptManager.bindSessionResources:233 | 旧OCR close失败阻止新run | 恢复上游只记录的语义，真实新OCR创建失败仍报错 |
| D12 | ScriptManager.releaseSessionResources:211–224→completeAutomation:270–273 | OCR/cache/录像清理失败统一占执行锁 | 无输入副作用资源错误降诊断；线程未退出/OS输入未释放仍占锁 |

| ID | Before-source function / lines | Trigger and consequence | Correct responsibility / disposition |
|---|---|---|---|
| D13 | ExternalPlanBattle.externalSkillsSceneVisible L49–54; requireStableExternalSkillsScene L56–60 | Attack absent OR SkillUse visible OR command-spell Cancel visible OR no ServantExist at .70; two samples separated by .5s; throws UnsupportedNativeSkillSceneEvidence. Six direct call sites L46,75,101,104,108,114, plus runner verifyScene at L29,36,40. | Delete the entire extra scene-proof function and all callers. Native AutoBattle owns turn entry; native Caster owns skill/UI waits. A fresh next observation reports facts without proving the previous effect. |
| D14 | ExternalPlanBattle.returnToExternalSkills L20–27 | Back then hard failure if Attack not found within new 5s deadline. | Delete added deadline and assertion. Preserve one native Back click and ordinary mechanical pause; reuse native Caster waits. |
| D15 | NativeDecisionTurnRunner.validateNativeHand L77–78; run L47/61; validateNativeDecision L108; ExternalCardExecutor L26/48 | Anything other than exactly five distinct slots stops native execution. | Observation completeness belongs to the external consumer; publish native facts, do not block an explicit plan. Delete helper and all references. |
| D16 | validateNativeHand L79–83 (same references as D15) | Unknown type/owner/fieldSlot/affinity rejects the entire hand. | Delete observation-quality precondition. Unknown values remain in DTO output. Basic action indices are separately retained. |
| D17 | ExternalCardExecutor.nativePane L28–40 / verifyHand L42–44 / deliverNativeCardOrder inspect L12–16 | Re-read requires all five attributes equal to the initial unselected hand; partial/changed selection graphics abort. Before snapshot has one initial transaction inspect plus runner scene checks; installed1fb also rereads per selection (historical cause). | Delete nativePane, verifyHand, NativeCardPane and inspection callbacks. Execute the explicit sequence through original Caster, without a new selected-card/effect detector. |
| D18 | NativeDeployment.validateNativeDeployment L17–36; ExternalPlanBattle L79–82; runner L37/41 | Caster, requested ally or order-change reserve not recognized => no action. | Delete all recognized-deployment gates. Keep numeric enum conversion/range. Original Caster owns its existing layout behavior; external policy owns its explicit intent. Remove the now-unused ServantTracker.reserves addition. |
| D19 | ExternalCardExecutor.executeOrder L49–53 | Any requested NP maps to Unknown/nondeployed tracker slot => entire sequence rejected. | Delete recognition prerequisite. Exact NP slot remains a required external parameter. |
| D20 | NativeDecisionTurnRunner.validateNativeDecision L102–104; ExternalPlanProtocol.validateAction L110; ExternalPlanReceiver.action L66 | enemyCount missing, >3, or index>=count rejects an otherwise explicit native A/B/C slot. | Delete inferred enemy-layout prerequisite and required decoding. Keep explicit index0..2 because upstream EnemyTarget exposes three positions. This does not add six-enemy targeting. |
| D21 | validateNativeDecision L110–112 | Requires >=3 ordinary nonstunned fallback cards even when three explicit NPs were supplied. | Delete this native card policy. FGOAgent chooses skills, NPs, faces and fallbacks. |
| D22 | ExternalFrameStore Entry.expiry L15; save L41; cleanup L60–62, called save/read | Arbitrary 5min age makes a retained frame unreadable. | Delete age expiry. Retain run release/restart token invalidation, private-path checks and bounded cache. |
| D23 | ExternalFrameStore init L20 | Failure deleting an old private capture prevents interface initialization. | Old tokens are not loaded after restart; log cleanup failure, do not block new unrelated captures. No old pathname is exposed. |
| D24 | NativeCardTransaction L20 order.take(3) | Android silently selects the first three from a longer external ranking. | Remove native truncation/selection. FGOAgent wire adapter explicitly records and submits the first three of the unchanged policy ranking; native receives an exact three-choice sequence and executes all of it. No candidate-fill feature is introduced. Wrong wire length is basic message shape, not native tactical selection. |


所有24族均为本fork新增；不修改官方原生正常的页面识别和动作等待。Caster warning-close扩展和ServantTracker.reserves getter另作恢复官方的diff精简，不虚算独立门槛。此前1fb已经删除的出卡后5秒效果/回合证明是历史删减，不重复计入本轮24。

## 收敛后的运行流程

官方AutoBattle识别可操作回合 → 导出原生事实和新截图 → FGOAgent独立观测适配与policy选择语义动作 → FGA以run/turn/observation身份接受并去重 → 直接调用官方Caster/Attack/Back执行 → 返回调用结果、不检查游戏效果 → 下一轮Smart读取新截图。三张明确卡序交回官方AutoBattle处理动画/新回合/结算。

FGOAgent保留完整policy候选排名，在计划组装时明确本次三张发送序列，FGA不静默选前三、不执行候补补卡。这个转换不改变旧实际前三顺序。条件目标框检测、候补补选继续TODO。

## 保留基础项（不属于战术/效果门槛）

1. Binder调用方UID白名单与只读frame URI、无任意路径/写入接口。
2. RPC版本/动作类型/字段/参数类型/范围与native枚举一致；协议大小、序列长度和唯一槽位。
3. 配置存在、明确服务器与配置绑定；Smart/script路径隔离。
4. 显式有限任务轮数、整任务时限、无补AP/令咒/付费消耗；单run冻结配置，不按全局UI瞬时值阻断。
5. Starting提前占位、单进程执行会话互斥、owner匹配；UI与API不能同时输入。
6. requestId/controlId/planId幂等与payload冲突检查、同一观察只接受一个动作；不重放已提交动作。
7. 正确的phase执行顺序、当前run/battle/turn/observation绑定；不以墙钟TTL代替身份。
8. 原子journal保存和schema兼容；保留去重身份的容量限制（任务/计划10000、控制256及stop预留），事件/图片缓存内存界限。
9. 用户暂停/停止、ExitManager取消，真实进程退出/失联、权限和捕获错误回报。
10. OS gesture真正接收/取消/回调及continued-pointer drain，真实native线程退出；释放控制的证据是输入停止，不是技能效果。
11. token格式、私有目录、文件大小/摘要和run结束失效；删除年龄型frame超时，不放开路径访问。
12. 原生Completed只代表调用返回，gameEffect始终unverified；真正执行器异常保留Unconfirmed/错误。无游戏效果确认也可正常继续。
13. 原生AutoBattle/Caster的原有识别、技能确认、目标布局处理、动画等待、结果统计不改；普通FGA模式保留原策略源码，外部Smart路径不调用。

这些是分组描述，不以13组声称底层检查恰好13条。每个文件覆盖与独立系统审查另附；所有限制值明确列出，不宣称完全删除协议/系统校验。

## Removal details and regression boundary

D13 removes the composite Attack-present/SkillUse-absent/Cancel-absent/ServantExist-present two-sample proof and every before/after-action caller. D14 removes its separate five-second Back deadline. D15–16 remove complete-five/known-color-owner-field-affinity gates from runner and card export; native unknowns are still exported. D17 removes nativePane, verifyHand and transaction inspection callbacks. D18–19 remove field/reserve/NP identity preconditions. D20 removes enemyCount inference from protocol, receiver and native validation while preserving explicit A/B/C range. D21 removes the three nonstunned ordinary-fallback policy. D22–23 remove the five-minute frame clock and fatal stale-cache deletion. D24 removes native take(3); the host now explicitly dispatches its unchanged first three choices, leaving full candidate ranking in policy/replay. No candidate fill is implemented.

Caster's custom closeWarning flag and ServantTracker's reserves getter are reverted to official bytes. They are ancillary diff reduction, not extra invented deletion counts. Card, AutoBattle and libautomata remain official bytes. Battle retains only external routing and native-auto-target isolation. System-side added code still contains necessary new RPC/lifecycle validation; it is not described as entirely original upstream code.

Regressions exercise NP-only plans, partial/unknown/stunned exported hands, scene changes after skills, absent recognition of explicit recipients, missing parent/host attestation, no observation deadline, exact three-choice wire mapping and original Caster calls. Invalid native indices, duplicate/stale identities, actual cancellation, journal failure, OS gesture drain and single-executor ownership remain rejected/visible. Mock and compile results do not prove live game effects. A new fixed APK requires separate installation approval; no production switch is implied.
