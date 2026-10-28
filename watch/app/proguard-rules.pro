# 手表端只有一个 Activity，没有反射、没有序列化框架，
# 默认规则就够；这里只把入口留死，免得 R8 把 MainActivity 的名字改掉导致
# manifest 里的 .MainActivity 找不到（AGP 会自己保，但写一条更安心）
-keep class online.wokaka.caojiwatch.MainActivity { *; }
