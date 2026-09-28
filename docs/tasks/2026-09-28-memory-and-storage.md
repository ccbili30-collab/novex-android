# 任务书：图片内存封顶 + 存储占用页补卡片数据桶

日期：2026-09-28　分支：memory-cap-and-storage　目标：next

## 背景

用户反馈内存 1-5G（GH#206 同病：native bitmap 堆积），并给存储截图：磁盘
数据 1.98GB。裁决：「（图片内存封顶）确实要修」+「加」（存储占用诊断页与
图片封顶一起排）。

## 决议

**A. 图片内存封顶**
1. MinisApp.newImageLoader 加固定内存缓存上限 128MB（Coil MemoryCache.Builder.
   maxSizeBytes）——图片像素工作台到顶自动丢最旧，磁盘文件不动。
2. FullscreenImageViewer.loadBitmap 退役「每次新建 ImageLoader」：改用全局
   单例（context.imageLoader 解析 ImageLoaderFactory）；加 `.size(2048)`
   降采样（复制/分享用途足够，4K 原图解码 38MB→约 16MB）。调用点
   （FullscreenImageViewer 三处+ImageGalleryViewer 一处）随之受益。

**B. 存储页补卡片数据桶**（StorageManagementScreen 已有 沙箱/数据库/会话
三桶，缺卡片数据）
1. overview 增加「卡片内容」（rewrite-content 减 revisions）与
   「卡片修订历史」（rewrite-content/revisions，CardStore 每次保存一个
   修订文件，无上限累积——1.98G 主嫌）两行；
2. 语言包 8 语言（de/fr/ja/ko/ru/zh/zh-rTW + 默认）同步新串；
3. 清理入口本轮不加（先可见性）；修订历史压缩/清理挂账。

## 验收

- 图片缓存占用封顶 128MB；全屏看图/复制不再新建 ImageLoader；
- 存储页出现卡片数据两行，能定位 1.98G 构成；
- CI 绿。
