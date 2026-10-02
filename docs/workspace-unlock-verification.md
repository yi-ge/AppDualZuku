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
