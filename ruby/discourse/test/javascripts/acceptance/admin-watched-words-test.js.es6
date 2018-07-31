import { acceptance } from "helpers/qunit-helpers";
acceptance("Admin - Watched Words", { loggedIn: true });

QUnit.test("list words in groups", async assert => {
  await visit("/admin/logs/watched_words/action/block");

  assert.ok(exists(".watched-words-list"));
  assert.ok(
    !exists(".watched-words-list .watched-word"),
    "Don't show bad words by default."
  );

  await fillIn(".admin-controls .controls input[type=text]", "li");

  assert.equal(
    find(".watched-words-list .watched-word").length,
    1,
    "When filtering, show words even if checkbox is unchecked."
  );

  await fillIn(".admin-controls .controls input[type=text]", "");

  assert.ok(
    !exists(".watched-words-list .watched-word"),
    "Clearing the filter hides words again."
  );

  await click(".show-words-checkbox");

  assert.ok(
    exists(".watched-words-list .watched-word"),
    "Always show the words when checkbox is checked."
  );

  await click(".nav-stacked .censor a");

  assert.ok(exists(".watched-words-list"));
  assert.ok(!exists(".watched-words-list .watched-word"), "Empty word list.");
});

QUnit.test("add words", async assert => {
  await visit("/admin/logs/watched_words/action/block");

  click(".show-words-checkbox");
  fillIn(".watched-word-form input", "poutine");

  await click(".watched-word-form button");

  let found = [];
  _.each(find(".watched-words-list .watched-word"), i => {
    if (
      $(i)
        .text()
        .trim() === "poutine"
    ) {
      found.push(true);
    }
  });
  assert.equal(found.length, 1);
});

QUnit.test("remove words", async assert => {
  await visit("/admin/logs/watched_words/action/block");
  await click(".show-words-checkbox");

  let word = null;

  _.each(find(".watched-words-list .watched-word"), i => {
    if (
      $(i)
        .text()
        .trim() === "anise"
    ) {
      word = i;
    }
  });

  await click("#" + $(word).attr("id"));

  assert.equal(find(".watched-words-list .watched-word").length, 1);
});
