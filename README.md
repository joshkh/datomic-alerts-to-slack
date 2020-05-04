### Development

Download NPM dependencies
```
$ yarn
```
Start the shadow-cljs server (this runs in the background)
```bash
$ npx shadow-cljs server
```
Connect a remote CLJS REPL to the nREPL port from the shadow-cljs server and then evaluate the following

```clojure
(shadow/watch :lambda)
(shadow/repl :lambda)
```

Open a process in NodeJS to evaluate ClojureScript REPL output
```bash
$ node dist/lambda/index.js
```