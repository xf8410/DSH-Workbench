# 内置桌宠：东海帝皇

`app/src/main/assets/pets/teio/` 里的一个 schema-1 宠物包，运行时由 `io.github.jackwener.dsh.pet` 渲染成桌面上的一只小马娘。
包格式与 `ccch1mneyyy/dsh-go` 的 `app/assets/pets/<id>/pack.json` 完全一致（画布 / 缩放 / 逐帧毫秒 / states 映射都同名同义），
所以同一个包既能被 dsh-go 导入，也能在这里直接内置。

## 怎么用

1. 手机上给 App 开「显示在其他应用上层」（系统设置 → 应用 → DSH → 悬浮窗）。
2. 下拉通知栏 → 编辑快捷开关 → 把「东海帝皇」拖出来 → 点一下开，再点一下关。
   - 打开时通知栏常驻一条「东海帝皇在你桌面上」，可以从那里收起。
3. 桌宠上：**点一下**有反应（`poke`），**双击**开心（`happy`），**拖动**移动，**长按**收起。

代码侧也可以直接调：

```kotlin
PetOverlay.show(context)    // 显示
PetOverlay.hide(context)    // 收起
PetOverlay.toggle(context)  // 切换
petView.setState("thinking")// 换成任意 states 名称
```

## 包内容

| 动画 | 帧 | 时长 | 内容 |
|---|---|---|---|
| idle | 12 | 2400 ms | 呼吸 + 眨眼 |
| type | 8 | 880 ms | 敲键盘 |
| happy | 8 | 760 ms | 举双手跳（一次性） |
| notify | 6 | 1020 ms | 挥手摇摆 |
| wave | 4 | 880 ms | 打招呼 |
| sleep | 4 | 2600 ms | 闭眼 + 睡眠气泡 |
| poke | 6 | 660 ms | 被戳惊讶（一次性） |
| think | 4 | 2080 ms | 思考 |
| cry | 4 | 1120 ms | 委屈 |
| annoyed | 4 | 680 ms | 鼓脸生气 |
| stretch | 6 | 900 ms | 伸懒腰（一次性） |
| look | 4 | 1600 ms | 歪头看你 |

`states` 把 App 状态映射到动画（Clawd on Desk 的命名）：
`idle / thinking / working / juggling / sweeping / notification / attention / error / carrying / sleeping / waking`。

## 代码结构

```
app/src/main/java/io/github/jackwener/dsh/pet/
  PetPack.kt            pack.json 解析 + 精灵表切片
  PetView.kt            逐帧动画 View（点/双击/长按/拖动）
  PetOverlayService.kt  前台悬浮服务 + PetOverlay 门面
  PetTile.kt            通知栏快捷开关
```

素材来源：Q 版母版与 11 张表情/动作帧由 `gpt-image-2.6-sunburst` 逐帧重画（只改会动的部位），
帧间按腿部剪影对齐到 ±2 px，再按母版统一缩放裁进 320×320 帧。
同人二创，仅供个人使用；角色版权属 Cygames。
