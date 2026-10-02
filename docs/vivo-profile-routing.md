# vivo 工作资料与私人资料启动（2026-10-02）

设备 V2545A / Android 16。Work1=user13、serial1003、MANAGED、parent0；Private1=user17、serial1007、PRIVATE、parent0。Work1 的 Profile Owner 正确，类型没有被修改成 PRIVATE。

## 桌面错误归属

只读分析设备公开 BBKLauncher2.apk。CtsUserManager.i 遍历资料列表，筛选非主用户及非特定系统分身后反复赋值工作用户，不检查 MANAGED 类型。这与“Private1加入后旧工作图标无公文包、暂停Private1后旧图标变灰而Work1仍RUNNING_UNLOCKED”的真机结果吻合。仅暂停或重启桌面不能修正其选择逻辑。没有修改桌面二进制、数据库或删除旧图标。

## 1.17 可用路径

按原有 serial/package 身份创建的 Work1 图标使用 PackageManager.getUserBadgedIcon，保留系统工作资料徽标；LauncherApps.getActivityList/startMainActivity 显式使用 Work1 的 UserHandle，校验 serial 和活动所属用户。已解锁的 MANAGED 应用启动不依赖 Shizuku。PRIVATE 不使用此快速路径，仍需用户单独启动。所有图标继续使用独立 URI，避免仅 extras 不同导致的复用。

Private Space 标准重启锁定设置 private_space_auto_lock=2 已写入并通过 shell 读回。vivo 直接 shell 写入曾未生效，使用受 DUMP 保护的主用户 Provider 和临时 WRITE_SECURE_SETTINGS 权限执行，随后立即撤销权限。该隐藏键的普通应用读回受限制，因此由 shell 核验。没有修改主用户密码或全局 provisioning。

## 真机结果

Private1 在启动过后进行重启。仅解锁主屏：Work1 RUNNING_UNLOCKED，Private1 quiet mode、未启动；Shizuku 未运行。新的 Work1 微信图标仍打开 user13，Private1未被启动。此前两个独立图标交替启动也已验证 user13/user17 路由正确。所有原有用户0/13/17/666/999及数据保留。

旧 vivo 原生图标尚不能原地修复；新图标恢复工作资料标识和正确启动用途，不代表系统桌面的错误枚举逻辑已被修补。安装、卸载及私人资料启动仍使用 Shizuku/Root；重启后管理操作需启动 Shizuku。
