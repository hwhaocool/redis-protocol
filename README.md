
redis-server 可以自定义 sleep x秒的 redis server
------

首先，代码 fork 自 (spullara)[https://github.com/spullara/redis-protocol]

改动点基本集中在 `netty4-server` 这个工程里面

## build

```
mvn install -DskipTests
```

当然了，也可以到 release 里面获取我打好的包，为了兼容，我用的java8 （源代码是 jdk7的）

## 使用

两个参数，当然了，可以用 `-h` 查看
```
java -jar redis-server.jar -h
[2020-06-30 09:44:01] INFO [ main] redis.server.netty.Main:32 - starting...
Usage: redis.server.netty.Main
  -port (-p) [Integer]  (6379)
  -blockSeconds (-b) [Integer]  (2)
```

|参数|作用|
|-|-|
|`-p`|端口号，默认`6379`|
|`-b`|sleep时间（单位：秒）|

（`ping` `command` 等 命令不会 sleep）

举例：

```
java -jar redis-server.jar -p 6379 -b 10
```