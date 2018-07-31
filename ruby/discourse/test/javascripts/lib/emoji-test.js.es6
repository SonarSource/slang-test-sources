import { emojiSearch, IMAGE_VERSION as v } from "pretty-text/emoji";
import { emojiUnescape } from "discourse/lib/text";

QUnit.module("lib:emoji");

QUnit.test("emojiUnescape", assert => {
  const testUnescape = (input, expected, description) => {
    assert.equal(emojiUnescape(input), expected, description);
  };

  testUnescape(
    "Not emoji :O) :frog) :smile)",
    "Not emoji :O) :frog) :smile)",
    "title without emoji"
  );
  testUnescape(
    "Not emoji :frog :smile",
    "Not emoji :frog :smile",
    "end colon is not optional"
  );
  testUnescape(
    "emoticons :)",
    `emoticons <img src='/images/emoji/emoji_one/slight_smile.png?v=${v}' title='slight_smile' alt='slight_smile' class='emoji'>`,
    "emoticons are still supported"
  );
  testUnescape(
    "With emoji :O: :frog: :smile:",
    `With emoji <img src='/images/emoji/emoji_one/o.png?v=${v}' title='O' alt='O' class='emoji'> <img src='/images/emoji/emoji_one/frog.png?v=${v}' title='frog' alt='frog' class='emoji'> <img src='/images/emoji/emoji_one/smile.png?v=${v}' title='smile' alt='smile' class='emoji'>`,
    "title with emoji"
  );
  testUnescape(
    "a:smile:a",
    "a:smile:a",
    "word characters not allowed next to emoji"
  );
  testUnescape(
    "(:frog:) :)",
    `(<img src='/images/emoji/emoji_one/frog.png?v=${v}' title='frog' alt='frog' class='emoji'>) <img src='/images/emoji/emoji_one/slight_smile.png?v=${v}' title='slight_smile' alt='slight_smile' class='emoji'>`,
    "non-word characters allowed next to emoji"
  );
  testUnescape(
    ":smile: hi",
    `<img src='/images/emoji/emoji_one/smile.png?v=${v}' title='smile' alt='smile' class='emoji'> hi`,
    "start of line"
  );
  testUnescape(
    "hi :smile:",
    `hi <img src='/images/emoji/emoji_one/smile.png?v=${v}' title='smile' alt='smile' class='emoji'>`,
    "end of line"
  );
  testUnescape(
    "hi :blonde_woman:t4:",
    `hi <img src='/images/emoji/emoji_one/blonde_woman/4.png?v=${v}' title='blonde_woman:t4' alt='blonde_woman:t4' class='emoji'>`,
    "support for skin tones"
  );
  testUnescape(
    "hi :blonde_woman:t4: :blonde_man:t6:",
    `hi <img src='/images/emoji/emoji_one/blonde_woman/4.png?v=${v}' title='blonde_woman:t4' alt='blonde_woman:t4' class='emoji'> <img src='/images/emoji/emoji_one/blonde_man/6.png?v=${v}' title='blonde_man:t6' alt='blonde_man:t6' class='emoji'>`,
    "support for multiple skin tones"
  );
  testUnescape(
    "hi :blonde_man:t6",
    "hi :blonde_man:t6",
    "end colon not optional for skin tones"
  );
});

QUnit.test("Emoji search", assert => {
  // able to find an alias
  assert.equal(emojiSearch("+1").length, 1);

  // able to find middle of line search
  assert.equal(emojiSearch("check", { maxResults: 3 }).length, 3);
});
