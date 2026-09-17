const assert = require('node:assert/strict');

require('../shared/glossary-core.js');

global.chrome = {
  storage: {
    local: {
      async get(defaults) {
        return {
          ...defaults,
          glossaryEnabled: true,
          glossaryCaseSensitive: false,
          glossaryEntries: [
            { source: 'OpenAI', target: 'OpenAI' },
            { source: 'AI', target: '人工智能' }
          ]
        };
      }
    }
  }
};

global.translateBatch = async texts => texts.map(text => `【${text}】`);
require('../background/glossary-runtime.js');

(async () => {
  const result = await global.translateBatch(['Use OpenAI and AI now.']);
  assert.deepEqual(result, ['【Use 】OpenAI【 and 】人工智能【 now.】']);

  const untouched = await global.translateBatch(['No fixed term here.']);
  assert.deepEqual(untouched, ['【No fixed term here.】']);

  console.log('glossary-runtime tests passed');
})().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
