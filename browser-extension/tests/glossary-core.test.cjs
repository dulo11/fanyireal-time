const assert = require('node:assert/strict');

require('../shared/glossary-core.js');
const FT = globalThis.FTGlossary;

assert.ok(FT, 'FTGlossary should be exported on globalThis');

const normalized = FT.normalizeEntries([
  { source: 'AI', target: '人工智能' },
  { source: 'OpenAI', target: 'OpenAI' },
  { source: '', target: 'x' },
  { source: 'AI', target: 'duplicate' }
]);
assert.equal(normalized.length, 2);
assert.equal(normalized[0].source, 'OpenAI', 'longer terms should be matched first');

const plan = FT.planText('Use OpenAI and AI today.', normalized, false);
assert.equal(FT.hasFixedTerms(plan), true);
const translateParts = plan.filter(item => item.type === 'translate').map(item => item.text);
assert.deepEqual(translateParts, ['Use ', ' and ', ' today.']);
assert.equal(
  FT.renderPlan(plan, ['使用', '和', '今天。']),
  '使用OpenAI和人工智能今天。'
);

const special = FT.planText('C++ and a.b', [
  { source: 'C++', target: 'C++' },
  { source: 'a.b', target: '固定点号' }
]);
assert.equal(FT.renderPlan(special, [' 与 ']), 'C++ 与 固定点号');

const insensitive = FT.planText('chatgpt ChatGPT', [{ source: 'ChatGPT', target: 'GPT' }], false);
assert.equal(FT.renderPlan(insensitive, [' ']), 'GPT GPT');

const sensitive = FT.planText('chatgpt ChatGPT', [{ source: 'ChatGPT', target: 'GPT' }], true);
assert.equal(FT.renderPlan(sensitive, ['chatgpt ']), 'chatgpt GPT');

console.log('glossary-core tests passed');
