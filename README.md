Unique IDs generator
=====================================

Produce ordered unique IDs (as 64 bits number). 

To run the server, install Play 2.2.2 (http://downloads.typesafe.com/play/2.2.2/play-2.2.2.zip), and use the `play dist`
command to generate a binary distribution of the server (will produce a `./target/universal/unique-ids-xxx.zip` file). 

Then unzip it and run `sh ./bin/unique-ids -mem 64`

To consume IDs, just use an HTTP client like 

`curl http://hostname:port/nextId` or `curl http://hostname:port/nextId.json`

You can use several nodes to serve IDs, just don't forget to customize your `generator.id` (up to 1024) in `application.conf`

```
generator {
   id=42
}
```

As these ids are ordered by time, you need to synchronize your nodes clock with NTP.

Some statistics are available at `http://hostname:port/stats` and will produce something like

```javascript
{
    "totalHits":14640246,
    "averageTimeNsPerHit":1503000,
    "averageRequestsPerSec":9983.8,
    "topRequestsPerSec":10843.4
}
```

you can reset stats with `curl -X DELETE http://hostname:port/stats`
you can turn stats off from your `application.conf` file

```
generator {
    id=42
    stats {
        enabled=false
    }
}
```


Expect something like `2ms` to `4ms` response time per request and `8k` to `10k` requests per second (on a warm JVM)

highly inspired by https://github.com/twitter/snowflake/
