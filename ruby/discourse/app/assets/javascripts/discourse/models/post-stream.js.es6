import { ajax } from "discourse/lib/ajax";
import DiscourseURL from "discourse/lib/url";
import RestModel from "discourse/models/rest";
import PostsWithPlaceholders from "discourse/lib/posts-with-placeholders";
import { default as computed } from "ember-addons/ember-computed-decorators";
import { loadTopicView } from "discourse/models/topic";

export default RestModel.extend({
  _identityMap: null,
  posts: null,
  stream: null,
  userFilters: null,
  summary: null,
  loaded: null,
  loadingAbove: null,
  loadingBelow: null,
  loadingFilter: null,
  loadingNearPost: null,
  stagingPost: null,
  postsWithPlaceholders: null,
  timelineLookup: null,

  init() {
    this._identityMap = {};
    const posts = [];
    const postsWithPlaceholders = PostsWithPlaceholders.create({
      posts,
      store: this.store
    });

    this.setProperties({
      posts,
      postsWithPlaceholders,
      stream: [],
      userFilters: [],
      summary: false,
      loaded: false,
      loadingAbove: false,
      loadingBelow: false,
      loadingFilter: false,
      stagingPost: false,
      timelineLookup: []
    });
  },

  loading: Ember.computed.or(
    "loadingAbove",
    "loadingBelow",
    "loadingFilter",
    "stagingPost"
  ),
  notLoading: Ember.computed.not("loading"),

  @computed("isMegaTopic", "stream.length", "topic.highest_post_number")
  filteredPostsCount(isMegaTopic, streamLength, topicHighestPostNumber) {
    return isMegaTopic ? topicHighestPostNumber : streamLength;
  },

  @computed("posts.[]")
  hasPosts() {
    return this.get("posts.length") > 0;
  },

  @computed("hasPosts", "filteredPostsCount")
  hasLoadedData(hasPosts, filteredPostsCount) {
    return hasPosts && filteredPostsCount > 0;
  },

  canAppendMore: Ember.computed.and(
    "notLoading",
    "hasPosts",
    "lastPostNotLoaded"
  ),
  canPrependMore: Ember.computed.and(
    "notLoading",
    "hasPosts",
    "firstPostNotLoaded"
  ),

  @computed("hasLoadedData", "firstPostId", "posts.[]")
  firstPostPresent(hasLoadedData, firstPostId) {
    if (!hasLoadedData) {
      return false;
    }
    return !!this.get("posts").findBy("id", firstPostId);
  },

  firstPostNotLoaded: Ember.computed.not("firstPostPresent"),

  firstId: null,
  lastId: null,

  @computed("isMegaTopic", "stream.firstObject", "firstId")
  firstPostId(isMegaTopic, streamFirstId, firstId) {
    return isMegaTopic ? firstId : streamFirstId;
  },

  @computed("isMegaTopic", "stream.lastObject", "lastId")
  lastPostId(isMegaTopic, streamLastId, lastId) {
    return isMegaTopic ? lastId : streamLastId;
  },

  @computed("hasLoadedData", "lastPostId", "posts.@each.id")
  loadedAllPosts(hasLoadedData, lastPostId) {
    if (!hasLoadedData) {
      return false;
    }
    if (lastPostId === -1) {
      return true;
    }

    return !!this.get("posts").findBy("id", lastPostId);
  },

  lastPostNotLoaded: Ember.computed.not("loadedAllPosts"),

  /**
    Returns a JS Object of current stream filter options. It should match the query
    params for the stream.
  **/
  @computed("summary", "userFilters.[]")
  streamFilters(summary) {
    const result = {};
    if (summary) {
      result.filter = "summary";
    }

    const userFilters = this.get("userFilters");
    if (!Ember.isEmpty(userFilters)) {
      result.username_filters = userFilters.join(",");
    }

    return result;
  },

  @computed("streamFilters.[]", "topic.posts_count", "posts.length")
  hasNoFilters() {
    const streamFilters = this.get("streamFilters");
    return !(
      streamFilters &&
      (streamFilters.filter === "summary" || streamFilters.username_filters)
    );
  },

  /**
    Returns the window of posts above the current set in the stream, bound to the top of the stream.
    This is the collection we'll ask for when scrolling upwards.
  **/
  @computed("posts.[]", "stream.[]")
  previousWindow() {
    // If we can't find the last post loaded, bail
    const firstPost = _.first(this.get("posts"));
    if (!firstPost) {
      return [];
    }

    // Find the index of the last post loaded, if not found, bail
    const stream = this.get("stream");
    const firstIndex = this.indexOf(firstPost);
    if (firstIndex === -1) {
      return [];
    }

    let startIndex = firstIndex - this.get("topic.chunk_size");
    if (startIndex < 0) {
      startIndex = 0;
    }
    return stream.slice(startIndex, firstIndex);
  },

  /**
    Returns the window of posts below the current set in the stream, bound by the bottom of the
    stream. This is the collection we use when scrolling downwards.
  **/
  @computed("posts.lastObject", "stream.[]")
  nextWindow(lastLoadedPost) {
    // If we can't find the last post loaded, bail
    if (!lastLoadedPost) {
      return [];
    }

    // Find the index of the last post loaded, if not found, bail
    const stream = this.get("stream");
    const lastIndex = this.indexOf(lastLoadedPost);
    if (lastIndex === -1) {
      return [];
    }
    if (lastIndex + 1 >= this.get("highest_post_number")) {
      return [];
    }

    // find our window of posts
    return stream.slice(
      lastIndex + 1,
      lastIndex + this.get("topic.chunk_size") + 1
    );
  },

  cancelFilter() {
    this.set("summary", false);
    this.get("userFilters").clear();
  },

  toggleSummary() {
    this.get("userFilters").clear();
    this.toggleProperty("summary");
    const opts = {};

    if (!this.get("summary")) {
      opts.filter = "none";
    }

    return this.refresh(opts).then(() => {
      if (this.get("summary")) {
        this.jumpToSecondVisible();
      }
    });
  },

  jumpToSecondVisible() {
    const posts = this.get("posts");
    if (posts.length > 1) {
      const secondPostNum = posts[1].get("post_number");
      DiscourseURL.jumpToPost(secondPostNum);
    }
  },

  // Filter the stream to a particular user.
  toggleParticipant(username) {
    const userFilters = this.get("userFilters");
    this.set("summary", false);

    let jump = false;
    if (userFilters.includes(username)) {
      userFilters.removeObject(username);
    } else {
      userFilters.addObject(username);
      jump = true;
    }
    return this.refresh().then(() => {
      if (jump) {
        this.jumpToSecondVisible();
      }
    });
  },

  /**
    Loads a new set of posts into the stream. If you provide a `nearPost` option and the post
    is already loaded, it will simply scroll there and load nothing.
  **/
  refresh(opts) {
    opts = opts || {};
    opts.nearPost = parseInt(opts.nearPost, 10);

    if (opts.cancelSummary) {
      this.set("summary", false);
      delete opts.cancelSummary;
    }

    const topic = this.get("topic");

    // Do we already have the post in our list of posts? Jump there.
    if (opts.forceLoad) {
      this.set("loaded", false);
    } else {
      const postWeWant = this.get("posts").findBy("post_number", opts.nearPost);
      if (postWeWant) {
        return Ember.RSVP.resolve();
      }
    }

    // TODO: if we have all the posts in the filter, don't go to the server for them.
    this.set("loadingFilter", true);
    this.set("loadingNearPost", opts.nearPost);

    opts = _.merge(opts, this.get("streamFilters"));

    // Request a topicView
    return loadTopicView(topic, opts)
      .then(json => {
        this.updateFromJson(json.post_stream);
        this.setProperties({
          loadingFilter: false,
          timelineLookup: json.timeline_lookup,
          loaded: true
        });
      })
      .catch(result => {
        this.errorLoading(result);
        throw new Error(result);
      })
      .finally(() => {
        this.set("loadingNearPost", null);
      });
  },

  // Fill in a gap of posts before a particular post
  fillGapBefore(post, gap) {
    const postId = post.get("id"),
      stream = this.get("stream"),
      idx = stream.indexOf(postId),
      currentPosts = this.get("posts");

    if (idx !== -1) {
      // Insert the gap at the appropriate place
      stream.splice.apply(stream, [idx, 0].concat(gap));

      let postIdx = currentPosts.indexOf(post);
      const origIdx = postIdx;
      if (postIdx !== -1) {
        return this.findPostsByIds(gap).then(posts => {
          posts.forEach(p => {
            const stored = this.storePost(p);
            if (!currentPosts.includes(stored)) {
              currentPosts.insertAt(postIdx++, stored);
            }
          });

          delete this.get("gaps.before")[postId];
          this.get("stream").enumerableContentDidChange();
          this.get("postsWithPlaceholders").arrayContentDidChange(
            origIdx,
            0,
            posts.length
          );
          post.set("hasGap", false);
        });
      }
    }
    return Ember.RSVP.resolve();
  },

  // Fill in a gap of posts after a particular post
  fillGapAfter(post, gap) {
    const postId = post.get("id"),
      stream = this.get("stream"),
      idx = stream.indexOf(postId);

    if (idx !== -1) {
      stream.pushObjects(gap);
      return this.appendMore().then(() => {
        delete this.get("gaps.after")[postId];
        this.get("stream").enumerableContentDidChange();
      });
    }
    return Ember.RSVP.resolve();
  },

  // Appends the next window of posts to the stream. Call it when scrolling downwards.
  appendMore() {
    // Make sure we can append more posts
    if (!this.get("canAppendMore")) {
      return Ember.RSVP.resolve();
    }

    const postsWithPlaceholders = this.get("postsWithPlaceholders");

    if (this.get("isMegaTopic")) {
      this.set("loadingBelow", true);

      const fakePostIds = _.range(-1, -this.get("topic.chunk_size"), -1);
      postsWithPlaceholders.appending(fakePostIds);

      return this.fetchNextWindow(
        this.get("posts.lastObject.post_number"),
        true,
        p => {
          this.appendPost(p);
        }
      ).finally(() => {
        postsWithPlaceholders.finishedAppending(fakePostIds);
        this.set("loadingBelow", false);
      });
    } else {
      const postIds = this.get("nextWindow");
      if (Ember.isEmpty(postIds)) return Ember.RSVP.resolve();
      this.set("loadingBelow", true);
      postsWithPlaceholders.appending(postIds);

      return this.findPostsByIds(postIds)
        .then(posts => {
          posts.forEach(p => this.appendPost(p));
          return posts;
        })
        .finally(() => {
          postsWithPlaceholders.finishedAppending(postIds);
          this.set("loadingBelow", false);
        });
    }
  },

  // Prepend the previous window of posts to the stream. Call it when scrolling upwards.
  prependMore() {
    // Make sure we can append more posts
    if (!this.get("canPrependMore")) {
      return Ember.RSVP.resolve();
    }

    if (this.get("isMegaTopic")) {
      this.set("loadingAbove", true);
      let prependedIds = [];

      return this.fetchNextWindow(
        this.get("posts.firstObject.post_number"),
        false,
        p => {
          this.prependPost(p);
          prependedIds.push(p.get("id"));
        }
      ).finally(() => {
        const postsWithPlaceholders = this.get("postsWithPlaceholders");
        postsWithPlaceholders.finishedPrepending(prependedIds);
        this.set("loadingAbove", false);
      });
    } else {
      const postIds = this.get("previousWindow");
      if (Ember.isEmpty(postIds)) return Ember.RSVP.resolve();
      this.set("loadingAbove", true);

      return this.findPostsByIds(postIds.reverse())
        .then(posts => {
          posts.forEach(p => this.prependPost(p));
        })
        .finally(() => {
          const postsWithPlaceholders = this.get("postsWithPlaceholders");
          postsWithPlaceholders.finishedPrepending(postIds);
          this.set("loadingAbove", false);
        });
    }
  },

  /**
    Stage a post for insertion in the stream. It should be rendered right away under the
    assumption that the post will succeed. We can then `commitPost` when it succeeds or
    `undoPost` when it fails.
  **/
  stagePost(post, user) {
    // We can't stage two posts simultaneously
    if (this.get("stagingPost")) {
      return "alreadyStaging";
    }

    this.set("stagingPost", true);

    const topic = this.get("topic");
    topic.setProperties({
      posts_count: (topic.get("posts_count") || 0) + 1,
      last_posted_at: new Date(),
      "details.last_poster": user,
      highest_post_number: (topic.get("highest_post_number") || 0) + 1
    });

    post.setProperties({
      post_number: topic.get("highest_post_number"),
      topic: topic,
      created_at: new Date(),
      id: -1
    });

    // If we're at the end of the stream, add the post
    if (this.get("loadedAllPosts")) {
      this.appendPost(post);
      this.get("stream").addObject(post.get("id"));
      return "staged";
    }

    return "offScreen";
  },

  // Commit the post we staged. Call this after a save succeeds.
  commitPost(post) {
    if (this.get("topic.id") === post.get("topic_id")) {
      if (this.get("loadedAllPosts")) {
        this.appendPost(post);
        this.get("stream").addObject(post.get("id"));
      }
    }

    this.get("stream").removeObject(-1);
    this._identityMap[-1] = null;
    this.set("stagingPost", false);
  },

  /**
    Undo a post we've staged in the stream. Remove it from being rendered and revert the
    state we changed.
  **/
  undoPost(post) {
    this.get("stream").removeObject(-1);
    this.get("postsWithPlaceholders").removePost(() =>
      this.posts.removeObject(post)
    );
    this._identityMap[-1] = null;

    const topic = this.get("topic");
    this.set("stagingPost", false);

    topic.setProperties({
      highest_post_number: (topic.get("highest_post_number") || 0) - 1,
      posts_count: (topic.get("posts_count") || 0) - 1
    });

    // TODO unfudge reply count on parent post
  },

  prependPost(post) {
    const stored = this.storePost(post);
    if (stored) {
      const posts = this.get("posts");
      posts.unshiftObject(stored);
    }

    return post;
  },

  appendPost(post) {
    const stored = this.storePost(post);
    if (stored) {
      const posts = this.get("posts");

      if (!posts.includes(stored)) {
        if (!this.get("loadingBelow")) {
          this.get("postsWithPlaceholders").appendPost(() =>
            posts.pushObject(stored)
          );
        } else {
          posts.pushObject(stored);
        }
      }

      if (stored.get("id") !== -1) {
        this.set("lastAppended", stored);
      }
    }
    return post;
  },

  removePosts(posts) {
    if (Ember.isEmpty(posts)) {
      return;
    }

    this.get("postsWithPlaceholders").refreshAll(() => {
      const allPosts = this.get("posts");
      const postIds = posts.map(p => p.get("id"));
      const identityMap = this._identityMap;

      this.get("stream").removeObjects(postIds);
      allPosts.removeObjects(posts);
      postIds.forEach(id => delete identityMap[id]);
    });
  },

  // Returns a post from the identity map if it's been inserted.
  findLoadedPost(id) {
    return this._identityMap[id];
  },

  loadPostByPostNumber(postNumber) {
    const url = `/posts/by_number/${this.get("topic.id")}/${postNumber}`;
    const store = this.store;

    return ajax(url).then(post => {
      return this.storePost(store.createRecord("post", post));
    });
  },

  loadNearestPostToDate(date) {
    const url = `/posts/by-date/${this.get("topic.id")}/${date}`;
    const store = this.store;

    return ajax(url).then(post => {
      return this.storePost(store.createRecord("post", post));
    });
  },

  loadPost(postId) {
    const url = "/posts/" + postId;
    const store = this.store;
    const existing = this._identityMap[postId];

    return ajax(url).then(p => {
      if (existing) {
        p.cooked = existing.cooked;
      }

      return this.storePost(store.createRecord("post", p));
    });
  },

  /**
    Finds and adds a post to the stream by id. Typically this would happen if we receive a message
    from the message bus indicating there's a new post. We'll only insert it if we currently
    have no filters.
  **/
  triggerNewPostInStream(postId) {
    const resolved = Ember.RSVP.Promise.resolve();

    if (!postId) {
      return resolved;
    }

    // We only trigger if there are no filters active
    if (!this.get("hasNoFilters")) {
      return resolved;
    }

    const loadedAllPosts = this.get("loadedAllPosts");

    if (this.get("stream").indexOf(postId) === -1) {
      this.get("stream").addObject(postId);
      if (loadedAllPosts) {
        this.set("loadingLastPost", true);
        return this.findPostsByIds([postId])
          .then(posts => {
            posts.forEach(p => this.appendPost(p));
          })
          .finally(() => {
            this.set("loadingLastPost", false);
          });
      }
    }

    return resolved;
  },

  triggerRecoveredPost(postId) {
    const existing = this._identityMap[postId];

    if (existing) {
      return this.triggerChangedPost(postId, new Date());
    } else {
      // need to insert into stream
      const url = `/posts/${postId}`;
      const store = this.store;

      return ajax(url).then(p => {
        const post = store.createRecord("post", p);
        const stream = this.get("stream");
        const posts = this.get("posts");
        this.storePost(post);

        // we need to zip this into the stream
        let index = 0;
        stream.forEach(pid => {
          if (pid < p.id) {
            index += 1;
          }
        });

        stream.insertAt(index, p.id);

        index = 0;
        posts.forEach(_post => {
          if (_post.id < p.id) {
            index += 1;
          }
        });

        if (index < posts.length) {
          this.get("postsWithPlaceholders").refreshAll(() => {
            posts.insertAt(index, post);
          });
        } else {
          if (post.post_number < posts[posts.length - 1].post_number + 5) {
            this.appendMore();
          }
        }
      });
    }
  },

  triggerDeletedPost(postId) {
    const existing = this._identityMap[postId];

    if (existing && !existing.deleted_at) {
      const url = "/posts/" + postId;
      const store = this.store;

      return ajax(url)
        .then(p => {
          this.storePost(store.createRecord("post", p));
        })
        .catch(() => {
          this.removePosts([existing]);
        });
    }
    return Ember.RSVP.Promise.resolve();
  },

  triggerChangedPost(postId, updatedAt, opts) {
    opts = opts || {};

    const resolved = Ember.RSVP.Promise.resolve();
    if (!postId) {
      return resolved;
    }

    const existing = this._identityMap[postId];
    if (existing && existing.updated_at !== updatedAt) {
      const url = "/posts/" + postId;
      const store = this.store;
      return ajax(url).then(p => {
        if (opts.preserveCooked) {
          p.cooked = existing.get("cooked");
        }

        this.storePost(store.createRecord("post", p));
      });
    }
    return resolved;
  },

  /**
    Returns the closest post given a postNumber that may not exist in the stream.
    For example, if the user asks for a post that's deleted or otherwise outside the range.
    This allows us to set the progress bar with the correct number.
  **/
  closestPostForPostNumber(postNumber) {
    if (!this.get("hasPosts")) {
      return;
    }

    let closest = null;
    this.get("posts").forEach(p => {
      if (!closest) {
        closest = p;
        return;
      }

      if (
        Math.abs(postNumber - p.get("post_number")) <
        Math.abs(closest.get("post_number") - postNumber)
      ) {
        closest = p;
      }
    });

    return closest;
  },

  // Get the index of a post in the stream. (Use this for the topic progress bar.)
  progressIndexOfPost(post) {
    return this.progressIndexOfPostId(post);
  },

  // Get the index in the stream of a post id. (Use this for the topic progress bar.)
  progressIndexOfPostId(post) {
    const postId = post.get("id");
    const index = this.get("stream").indexOf(postId);

    if (this.get("isMegaTopic")) {
      return post.get("post_number");
    } else {
      return index + 1;
    }
  },

  /**
    Returns the closest post number given a postNumber that may not exist in the stream.
    For example, if the user asks for a post that's deleted or otherwise outside the range.
    This allows us to set the progress bar with the correct number.
  **/
  closestPostNumberFor(postNumber) {
    if (!this.get("hasPosts")) {
      return;
    }

    let closest = null;
    this.get("posts").forEach(p => {
      if (closest === postNumber) {
        return;
      }
      if (!closest) {
        closest = p.get("post_number");
      }

      if (
        Math.abs(postNumber - p.get("post_number")) <
        Math.abs(closest - postNumber)
      ) {
        closest = p.get("post_number");
      }
    });

    return closest;
  },

  closestDaysAgoFor(postNumber) {
    const timelineLookup = this.get("timelineLookup") || [];

    let low = 0;
    let high = timelineLookup.length - 1;

    while (low <= high) {
      const mid = Math.floor(low + (high - low) / 2);
      const midValue = timelineLookup[mid][0];

      if (midValue > postNumber) {
        high = mid - 1;
      } else if (midValue < postNumber) {
        low = mid + 1;
      } else {
        return timelineLookup[mid][1];
      }
    }

    const val = timelineLookup[high] || timelineLookup[low];
    if (val) return val[1];
  },

  // Find a postId for a postNumber, respecting gaps
  findPostIdForPostNumber(postNumber) {
    const stream = this.get("stream"),
      beforeLookup = this.get("gaps.before"),
      streamLength = stream.length;

    let sum = 1;
    for (let i = 0; i < streamLength; i++) {
      const pid = stream[i];

      // See if there are posts before this post
      if (beforeLookup) {
        const before = beforeLookup[pid];
        if (before) {
          for (let j = 0; j < before.length; j++) {
            if (sum === postNumber) {
              return pid;
            }
            sum++;
          }
        }
      }

      if (sum === postNumber) {
        return pid;
      }
      sum++;
    }
  },

  updateFromJson(postStreamData) {
    const posts = this.get("posts");

    const postsWithPlaceholders = this.get("postsWithPlaceholders");
    postsWithPlaceholders.clear(() => posts.clear());

    this.set("gaps", null);
    if (postStreamData) {
      // Load posts if present
      const store = this.store;
      postStreamData.posts.forEach(p =>
        this.appendPost(store.createRecord("post", p))
      );
      delete postStreamData.posts;

      // Update our attributes
      this.setProperties(postStreamData);
    }
  },

  /**
    Stores a post in our identity map, and sets up the references it needs to
    find associated objects like the topic. It might return a different reference
    than you supplied if the post has already been loaded.
  **/
  storePost(post) {
    // Calling `Ember.get(undefined)` raises an error
    if (!post) {
      return;
    }

    const postId = Ember.get(post, "id");
    if (postId) {
      const existing = this._identityMap[post.get("id")];

      // Update the `highest_post_number` if this post is higher.
      const postNumber = post.get("post_number");
      if (
        postNumber &&
        postNumber > (this.get("topic.highest_post_number") || 0)
      ) {
        this.set("topic.highest_post_number", postNumber);
        this.set("topic.last_posted_at", post.get("created_at"));
      }

      if (existing) {
        // If the post is in the identity map, update it and return the old reference.
        existing.updateFromPost(post);
        return existing;
      }

      post.set("topic", this.get("topic"));
      this._identityMap[post.get("id")] = post;
    }
    return post;
  },

  fetchNextWindow(postNumber, asc, callback) {
    const url = `/t/${this.get("topic.id")}/posts.json`;
    let data = {
      post_number: postNumber,
      asc: asc
    };

    data = _.merge(data, this.get("streamFilters"));
    const store = this.store;

    return ajax(url, { data }).then(result => {
      if (result.suggested_topics) {
        this.set("topic.suggested_topics", result.suggested_topics);
      }

      const posts = Ember.get(result, "post_stream.posts");

      if (posts) {
        posts.forEach(p => {
          p = this.storePost(store.createRecord("post", p));

          if (callback) {
            callback.call(this, p);
          }
        });
      }
    });
  },

  findPostsByIds(postIds) {
    const identityMap = this._identityMap;
    const unloaded = postIds.filter(p => !identityMap[p]);

    // Load our unloaded posts by id
    return this.loadIntoIdentityMap(unloaded).then(() => {
      return postIds.map(p => identityMap[p]).compact();
    });
  },

  loadIntoIdentityMap(postIds) {
    if (Ember.isEmpty(postIds)) {
      return Ember.RSVP.resolve([]);
    }

    const url = "/t/" + this.get("topic.id") + "/posts.json";
    const data = { post_ids: postIds };
    const store = this.store;

    return ajax(url, { data }).then(result => {
      if (result.suggested_topics) {
        this.set("topic.suggested_topics", result.suggested_topics);
      }

      const posts = Ember.get(result, "post_stream.posts");

      if (posts) {
        posts.forEach(p => this.storePost(store.createRecord("post", p)));
      }
    });
  },

  backfillExcerpts(streamPosition) {
    this._excerpts = this._excerpts || [];
    const stream = this.get("stream");

    this._excerpts.loadNext = streamPosition;

    if (this._excerpts.loading) {
      return this._excerpts.loading.then(() => {
        if (!this._excerpts[stream[streamPosition]]) {
          if (this._excerpts.loadNext === streamPosition) {
            return this.backfillExcerpts(streamPosition);
          }
        }
      });
    }

    let postIds = stream.slice(
      Math.max(streamPosition - 20, 0),
      streamPosition + 20
    );

    for (let i = postIds.length - 1; i >= 0; i--) {
      if (this._excerpts[postIds[i]]) {
        postIds.splice(i, 1);
      }
    }

    let data = {
      post_ids: postIds
    };

    this._excerpts.loading = ajax(
      "/t/" + this.get("topic.id") + "/excerpts.json",
      { data }
    )
      .then(excerpts => {
        excerpts.forEach(obj => {
          this._excerpts[obj.post_id] = obj;
        });
      })
      .finally(() => {
        this._excerpts.loading = null;
      });

    return this._excerpts.loading;
  },

  excerpt(streamPosition) {
    if (this.get("isMegaTopic")) {
      return new Ember.RSVP.Promise(resolve => resolve(""));
    }

    const stream = this.get("stream");

    return new Ember.RSVP.Promise((resolve, reject) => {
      let excerpt = this._excerpts && this._excerpts[stream[streamPosition]];

      if (excerpt) {
        resolve(excerpt);
        return;
      }

      this.backfillExcerpts(streamPosition)
        .then(() => {
          resolve(this._excerpts[stream[streamPosition]]);
        })
        .catch(e => reject(e));
    });
  },

  indexOf(post) {
    return this.get("stream").indexOf(post.get("id"));
  },

  // Handles an error loading a topic based on a HTTP status code. Updates
  // the text to the correct values.
  errorLoading(result) {
    const status = result.jqXHR.status;

    const topic = this.get("topic");
    this.set("loadingFilter", false);
    topic.set("errorLoading", true);

    // If the result was 404 the post is not found
    // If it was 410 the post is deleted and the user should not see it
    if (status === 404 || status === 410) {
      topic.set("notFoundHtml", result.jqXHR.responseText);
      return;
    }

    // If the result is 403 it means invalid access
    if (status === 403) {
      topic.set("noRetry", true);
      if (Discourse.User.current()) {
        topic.set("message", I18n.t("topic.invalid_access.description"));
      } else {
        topic.set("message", I18n.t("topic.invalid_access.login_required"));
      }
      return;
    }

    // Otherwise supply a generic error message
    topic.set("message", I18n.t("topic.server_error.description"));
  }
});
