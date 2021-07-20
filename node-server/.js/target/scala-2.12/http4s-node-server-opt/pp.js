const fs = require('fs')
const lineReader = require('line-reader');

const sourceMap = require('source-map')

const raw = JSON.parse(fs.readFileSync('main.js.map'))

sourceMap.SourceMapConsumer.with(raw, null, consumer => {
  
  const re = /LazyCompile: .+main.js:(\d+):(\d+)/
  lineReader.eachLine('processed.txt', ll => {
    if (re.test(ll)) {
      const [x, l, c] = ll.match(re)
      const { source, line, column } = consumer.originalPositionFor({
        line: Number(l),
        column: Number(c)
      })
      console.log(ll.replace(x, `${source}:${line}:${column}`))
    } else {
      console.log(ll)
    }
  });

})
