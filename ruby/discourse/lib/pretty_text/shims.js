__PrettyText = require('pretty-text/pretty-text').default;
__buildOptions = require('pretty-text/pretty-text').buildOptions;
__performEmojiUnescape = require('pretty-text/emoji').performEmojiUnescape;

__utils = require('discourse/lib/utilities');

__emojiUnicodeReplacer = null;

__setUnicode = function(replacements) {
  let unicodeRegexp = new RegExp(Object.keys(replacements).sort().reverse().join("|"), "g");

  __emojiUnicodeReplacer = function(text) {
    unicodeRegexp.lastIndex = 0;
    let m;
    while ((m = unicodeRegexp.exec(text)) !== null) {
      let replacement = ":" + replacements[m[0]] + ":";
      const before = text.charAt(m.index-1);
      if (!/\B/.test(before)) {
        replacement = "\u200b" + replacement;
      }
      text = text.replace(m[0], replacement);
    }
    return text;
  };
};

__paths = {};

function __getURLNoCDN(url) {
  if (!url) return url;

  // if it's a non relative URL, return it.
  if (url !== '/' && !/^\/[^\/]/.test(url)) { return url; }

  if (url.indexOf(__paths.baseUri) !== -1) { return url; }
  if (url[0] !== "/") url = "/" + url;

  return __paths.baseUri + url;
}

function __getURL(url) {
  url = __getURLNoCDN(url);
  // only relative urls
  if (__paths.CDN && /^\\\/[^\\\/]/.test(url)) {
    url = __paths.CDN + url;
  } else if (__paths.S3CDN) {
    url = url.replace(__paths.S3BaseUrl, __paths.S3CDN);
  }
  return url;
}

function __lookupInlineOnebox(url) {
  return __helpers.lookup_inline_onebox(url);
}

function __lookupImageUrls(urls) {
  return __helpers.lookup_image_urls(urls);
}

function __getTopicInfo(i) {
  return __helpers.get_topic_info(i);
}

function __categoryLookup(c) {
  return __helpers.category_tag_hashtag_lookup(c);
}

function __mentionLookup(u) {
  return __helpers.mention_lookup(u);
}

function __lookupAvatar(p) {
  return __utils.avatarImg({size: "tiny", avatarTemplate: __helpers.avatar_template(p) }, __getURL);
}

function __formatUsername(username) {
  return __helpers.format_username(username);
}

function __lookupPrimaryUserGroup(username) {
  return __helpers.lookup_primary_user_group(username);
}

function __getCurrentUser(userId) {
  return __helpers.get_current_user(userId);
}

I18n = {
  t: function(a,b) { return __helpers.t(a,b); }
};
