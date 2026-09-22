# GoGoGo Route Playback V1 — 测试版补丁

## 目标

本 V1 仅实现影梭 App **自身地图中的 GPX 路线回放**：

- 手机选择 GPX
- GPS/WGS84 → 百度地图坐标转换
- 按测试速度重采样
- 开始 / 暂停 / 继续 / 停止
- 循环回放
- Marker 沿路线移动

**本补丁没有把 RoutePlayer 接入 `ServiceGo.setTestProviderLocation()`，也不会向第三方 App 自动输出整条路线。**

基线：GoGoGo `master`，项目当前为 Java 11、compileSdk 32、BaiduLBS Android JAR。

---

## 1. 复制新增文件

把补丁里的这些文件复制到原项目对应位置：

```text
app/src/main/java/com/zcshou/route/RoutePoint.java
app/src/main/java/com/zcshou/route/GpxParser.java
app/src/main/java/com/zcshou/route/RouteInterpolator.java
app/src/main/java/com/zcshou/route/RoutePlayer.java
app/src/main/java/com/zcshou/gogogo/RouteActivity.java
app/src/main/res/layout/activity_route.xml
```

---

## 2. 修改 AndroidManifest.xml

在 `<application>` 内、`</application>` 前加入：

```xml
<activity
    android:name=".RouteActivity"
    android:label="路线回放"
    android:exported="false" />
```

---

## 3. 修改 app/src/main/res/menu/menu_main.xml

在现有 `action_search` 后增加：

```xml
<item
    android:id="@+id/action_route"
    android:title="路线回放"
    app:showAsAction="never" />
```

---

## 4. 修改 MainActivity.java

在 `MainActivity` 类中增加：

```java
@Override
public boolean onOptionsItemSelected(@NonNull MenuItem item) {
    if (item.getItemId() == R.id.action_route) {
        startActivity(new Intent(this, RouteActivity.class));
        return true;
    }
    return super.onOptionsItemSelected(item);
}
```

`MainActivity.java` 当前已经 import 了：

```java
android.content.Intent
android.view.MenuItem
androidx.annotation.NonNull
```

因此无需额外 import。

---

## 5. 编译

Windows / Android Studio Terminal：

```powershell
.\gradlew.bat assembleDebug
```

项目当前 Gradle 配置会把 Debug APK 命名为类似：

```text
Go_1.12.3_arm64-v8a_debug.apk
```

通常位于：

```text
app\build\outputs\apk\debug\
```

---

## 6. 手机端 V1 使用

1. 安装 Debug APK。
2. 打开影梭。
3. 右上角菜单 → `路线回放`。
4. `导入 GPX`。
5. 输入测试速度（例如 2.0 m/s）。
6. 是否勾选 `循环`。
7. 点击 `开始`。
8. Marker 会在影梭自己的百度地图里沿 GPX 自动移动。
9. 用 `暂停 / 继续 / 停止` 控制。

V1 先验证：
- GPX 能否正确读取；
- 百度地图位置是否对齐；
- 弯道是否连续；
- 循环衔接是否正常；
- 锁屏/切后台后的 Activity 生命周期行为。

---

## 7. 推荐下一阶段

V2 再做：
- 手工在地图上添加路线点；
- 保存/加载路线；
- 更平滑的地图跟随；
- 路线进度与累计距离；
- 前台 Service 保持路线播放器；
- 单元测试与异常 GPX 校验。

不要一开始同时修改 `ServiceGo`，否则 UI、坐标转换、RoutePlayer 和 Mock Provider 的问题会混在一起，不利于调试。
