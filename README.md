## Play-Google介绍

Play-Google是一款基于PlayFramework开发的服务器端软件，利用Play-Google可以很容易的搭建一台属于自己的Google搜索服务器。

## 开始使用

单击[Play-Google](http://pan.baidu.com/share/home?uk=2955928176#category/type=0)下载编译后的分发包，将分发包上传至服务器并解压。请确认您安装了Java8运行环境，
进入`play-google/bin`目录执行以下命令启动服务：

```nohup ./play-google -J-Xmx300m -Dhttp.port=8080 > ../log.txt&```

访问`http://server_ip:8080`测试启动是否成功。

## 设置Http代理
打开`conf/application.conf`,默认禁用Http代理，配置如下：
```
httpProxy {
  enable: false
  list = ["127.0.0.1:8080"]
}
```
若要启用Http代理，修改配置如下：
```
httpProxy {
  enable: true
  list = ["127.0.0.1:8080"]
}
```