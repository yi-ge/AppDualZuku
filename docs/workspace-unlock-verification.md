# 工作空间解锁 / 解除暂停（AppDual Dev 1.12）

## 修复内容

空间启动、应用启动、桌面快捷方式和平板显示共用 WorkspaceRepository.startWorkspace。暂停中的空间显示“解锁 / 解除暂停”。ProfileUnlockProcess 在当前 Shizuku shell 或已有 Root 执行模式中加载当前 APK，以方法名调用 IUserManager；校验资料类型及父用户，调用 requestQuietModeEnabled(false)，flags=0，保留系统身份验证。

系统返回 CREDENTIAL_REQUIRED 后，不立即判为失败：最多等待约两分钟，读取资料启动状态，仅当状态为 RUNNING_UNLOCKED 才继续启动目标应用。普通 start-user 成功也需要检查解锁状态。错误弹窗保留实际输出。已解锁的空间不会被重新暂停；已启动但未解锁的空间可重新进入系统解锁流程。

不读取密码、令牌或微信数据，不移除空间锁、不删除资料、不修改全局 provisioning 设置、不绕过非导出组件。

## 真机证据

Xiaomi Android 17：Private1 重启后处于 QUIET_MODE 且未运行；历史 Work1 正常。系统请求确实弹出密码验证。此前主用户验证通过后立即读取曾得到 RUNNING_LOCKED，这不能当作最终失败；后续资料变为 RUNNING_UNLOCKED，flags 恢复 0x1010。

AppDual 快捷方式通过 Shizuku 打开第四份微信，Activity 属于 user 10，Recents task 785 的 userId=10、mUserSetupComplete=true。未读取登录输入或聊天内容。

读取设备的公开 Settings APK，确认其内部验证 Activity 包含 Private Profile 的 force-verify 分支。内部 Activity 不能被外部 shell 直接启动；正常入口是系统的 quiet-mode 请求，而不是尝试绕过 exported 限制。

## 回归

24 项 JVM 测试通过，包括系统要求凭据时不提前启动、异步解锁后自动继续、未完成验证超时不启动、锁定状态不误报成功及错误输出保留。诊断 APK 可覆盖现有 AppDual Dev，原签名 AppDual 保持独立。

受控复测发现验证过程中 am get-started-user-state 可能以非零退出码返回 User is not started；现已将此状态纳入等待，新增对应回归测试。用户完成受控暂停 / 系统密码验证后确认自动打开微信成功；ADB 再次确认 Private1 为 RUNNING_UNLOCKED，用户集合保持 0/10/11/999，未删除任何已有空间。不是对其他 ROM 的兼容保证，也没有将手机重启后的 Shizuku 自动启动纳入实现。


## 1.14：重启后卡住的后续修复

1.12 单次受控暂停验证通过不能证明下一次重启也正常。后续重启复现：主用户验证成功，但 Private1 仍未解锁。1.13 的重新暂停重试不正确，会在凭据验证前再次停止资料，已移除；临时强制主用户 strong-auth 的候选路径也已移除，最终修复不修改此状态。

1.14 仅调整 PRIVATE 生命周期：先 am start-user 并确认资料运行，再调用正常 requestQuietModeEnabled(false, flags=0)，不再重新暂停它；等待 RUNNING_UNLOCKED 后继续启动。读取设备公开 Settings APK 与 AOSP LockSettingsService 的验证流程，定位“资料运行时验证才解锁子资料、未运行时只缓存凭据”的差异。

真机日志在 19:13:33 出现 Verifying lockscreen credential for user 10 与 Successfully verified lockscreen credential for user 10，Private1 变为 RUNNING_UNLOCKED，微信 WelcomeActivity 归属 user 10；用户确认已进入微信。24 项测试通过。最终冷启动复测正在进行，尚未发布 1.14。

## 1.15 界面更新与最终验证

用户确认重启后的手动流程成功。保留 PRIVATE 先运行、再系统验证的流程，不重新暂停资料，也不修改主用户 strong-auth 状态。界面新增清晰的启动进度、实时状态、返回空间设置入口；空间卡片按类型、初始化状态与锁定/暂停/启动状态显示，删除按钮降低视觉权重。底部导航预留内容间距，新建按钮避免文字换行。

最终诊断包 1.15 成功覆盖安装，没有清除 AppDual 或微信数据；24 项测试通过、git diff --check 通过。真机检查深色设置页面及空间卡片；最终 APK 的快捷方式再次打开 user 10 微信 WelcomeActivity，RUNNING_UNLOCKED。Shizuku 需要重启后自行启动；首次 Binder 到达前增加短暂连接重试，不能据此宣称服务可自动启动。
