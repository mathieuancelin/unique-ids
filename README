Unique IDs generator
=====================================

Produce unique IDs (as 64 bits number) that are ordered.

`curl http://hostname:port/nextId`

or

`curl http://hostname:port/nextId.json`

You can use several nodes to serve IDs, just don't forget to customize your `generator.id (up to 1024) in `application.conf`

```
generator {
   id=42
}
```

As these ids are ordered by time, you need to synchronize your nodes clock with NTP.

Some statistics are available at

`curl http://hostname:port/stats`

and will produce

```javascript
{"totalHits":1,"averageTimeNsPerHit":120503000,"averageRequestsPerSec":0.025}
```

you can turn stats off from your `application.conf` file

```
generator {
    id=42
    stats {
        print=false
        enabled=true
    }
}
```

highly inspired by https://github.com/twitter/snowflake/
