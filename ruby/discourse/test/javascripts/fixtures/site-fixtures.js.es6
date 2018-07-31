export default {
  "site.json": {
    site: {
      default_archetype: "regular",
      disabled_plugins: [],
      shared_drafts_category_id: 24,
      notification_types: {
        mentioned: 1,
        replied: 2,
        quoted: 3,
        edited: 4,
        liked: 5,
        private_message: 6,
        invited_to_private_message: 7,
        invitee_accepted: 8,
        posted: 9,
        moved_post: 10,
        linked: 11,
        granted_badge: 12
      },
      post_types: {
        regular: 1,
        moderator_action: 2,
        small_action: 3,
        whisper: 4
      },
      group_names: [
        "admins",
        "discourse",
        "everyone",
        "mcneel",
        "moderators",
        "newrelic",
        "plugin_authors",
        "sitepoint",
        "staff",
        "translators",
        "trust_level_0",
        "trust_level_1",
        "trust_level_2",
        "trust_level_3",
        "trust_level_4",
        "ubuntu"
      ],
      filters: [
        "latest",
        "unread",
        "new",
        "starred",
        "read",
        "posted",
        "search"
      ],
      periods: ["yearly", "quarterly", "monthly", "weekly", "daily"],
      top_menu_items: [
        "latest",
        "unread",
        "new",
        "starred",
        "read",
        "posted",
        "category",
        "categories",
        "top"
      ],
      anonymous_top_menu_items: [
        "latest",
        "top",
        "categories",
        "category",
        "categories",
        "top"
      ],
      uncategorized_category_id: 17,
      is_readonly: false,
      categories: [
        {
          id: 3,
          name: "meta",
          color: "aaa",
          text_color: "FFFFFF",
          slug: "meta",
          topic_count: 122,
          post_count: 1023,
          description:
            "Discussion about meta.discourse.org itself, the organization of this forum about Discourse, how it works, and how we can improve this site.",
          topic_url: "/t/category-definition-for-meta/24",
          read_restricted: false,
          permission: 1,
          notification_level: null,
          logo_url: null,
          background_url: null,
          show_subcategory_list: false,
          default_view: "latest"
        },
        {
          id: 10,
          name: "howto",
          color: "76923C",
          text_color: "FFFFFF",
          slug: "howto",
          topic_count: 72,
          post_count: 1022,
          description:
            "Tutorial topics that describe how to set up, configure, or install Discourse using a specific platform or environment. Topics in this category may only be created by trust level 2 and up. ",
          topic_url: "/t/category-definition-for-howto/2629",
          read_restricted: false,
          permission: 1,
          notification_level: null,
          logo_url: null,
          background_url: null,
          show_subcategory_list: false,
          default_view: "latest"
        },
        {
          id: 26,
          name: "spec",
          color: "33B0B0",
          text_color: "FFFFFF",
          slug: "spec",
          topic_count: 20,
          post_count: 278,
          description:
            "My idea here is to have mini specs for features we would like built but have no bandwidth to build",
          topic_url: "/t/about-the-spec-category/13965",
          read_restricted: false,
          permission: 1,
          parent_category_id: 2,
          notification_level: null,
          logo_url: null,
          background_url: null
        },
        {
          id: 7,
          name: "dev",
          color: "000",
          text_color: "FFFFFF",
          slug: "dev",
          topic_count: 481,
          post_count: 3575,
          description:
            "This category is for topics related to hacking on Discourse: submitting pull requests, configuring development environments, coding conventions, and so forth.",
          topic_url: "/t/category-definition-for-dev/1026",
          read_restricted: false,
          permission: 1,
          notification_level: null,
          logo_url: null,
          background_url: null,
          show_subcategory_list: true,
          default_view: "latest",
          subcategory_list_style: "boxes_with_featured_topics"
        },
        {
          id: 6,
          name: "support",
          color: "b99",
          text_color: "FFFFFF",
          slug: "support",
          topic_count: 1603,
          post_count: 11075,
          description:
            "Support on configuring, using, and installing Discourse. Not for software development related topics, but for admins and end users configuring and using Discourse.",
          topic_url: "/t/category-definition-for-support/389",
          read_restricted: false,
          permission: 1,
          notification_level: null,
          logo_url: null,
          background_url: null,
          show_subcategory_list: false,
          default_view: "latest"
        },
        {
          id: 24,
          name: "Shared Drafts",
          color: "92278F",
          text_color: "FFFFFF",
          slug: "shared-drafts",
          topic_count: 13,
          post_count: 53,
          description: "An area for staff members to post shared drafts",
          topic_url: "/t/about-the-shared-drafts-category/13110",
          read_restricted: true,
          permission: 1,
          notification_level: null,
          logo_url: null,
          background_url: null
        },
        {
          id: 28,
          name: "hack night",
          color: "B3B5B4",
          text_color: "FFFFFF",
          slug: "hack-night",
          topic_count: 8,
          post_count: 33,
          description:
            'This is a special, temporary category to organize work on the <a href="http://www.meetup.com/torontoruby/events/192168702/">Discourse Hack Night</a> in Toronto. ',
          topic_url: "/t/about-the-hack-night-category/17878",
          read_restricted: false,
          permission: 1,
          parent_category_id: 7,
          notification_level: null,
          logo_url: null,
          background_url: null
        },
        {
          id: 27,
          name: "translations",
          color: "808281",
          text_color: "FFFFFF",
          slug: "translations",
          topic_count: 95,
          post_count: 827,
          description:
            "This category is for discussion about localizing Discourse.",
          topic_url: "/t/about-the-translations-category/14549",
          read_restricted: false,
          permission: 1,
          parent_category_id: 7,
          notification_level: null,
          logo_url: null,
          background_url: null
        },
        {
          id: 4,
          name: "faq",
          color: "33b",
          text_color: "FFFFFF",
          slug: "faq",
          topic_count: 48,
          post_count: 501,
          description:
            "Topics that come up very often when discussing Discourse will eventually be classified into this Frequently Asked Questions category. Should only be added to popular topics.",
          topic_url: "/t/category-definition-for-faq/25",
          read_restricted: false,
          permission: 1,
          notification_level: null,
          logo_url: null,
          background_url: null,
          show_subcategory_list: false,
          default_view: "latest"
        },
        {
          id: 14,
          name: "marketplace",
          color: "8C6238",
          text_color: "FFFFFF",
          slug: "marketplace",
          topic_count: 66,
          post_count: 361,
          description:
            "About commercial Discourse related stuff: jobs or paid gigs, plugins, themes, hosting, etc.",
          topic_url: "/t/category-definition-for-marketplace/5425",
          read_restricted: false,
          permission: 1,
          notification_level: null,
          logo_url: null,
          background_url: null,
          show_subcategory_list: false,
          default_view: "latest"
        },
        {
          id: 12,
          name: "discourse hub",
          color: "b2c79f",
          text_color: "FFFFFF",
          slug: "discourse-hub",
          topic_count: 10,
          post_count: 164,
          description:
            "Topics about current or future Discourse Hub functionality at discourse.org including nickname registration, global user pages, and the site directory.",
          topic_url: "/t/category-definition-for-discourse-hub/3038",
          read_restricted: false,
          permission: 1,
          notification_level: null,
          logo_url: null,
          background_url: null,
          show_subcategory_list: false,
          default_view: "latest"
        },
        {
          id: 13,
          name: "blog",
          color: "ED207B",
          text_color: "FFFFFF",
          slug: "blog",
          topic_count: 22,
          post_count: 390,
          description:
            "Discussion topics generated from the official Discourse Blog. These topics are linked from the bottom of each blog entry where the blog comments would normally be.",
          topic_url: "/t/category-definition-for-blog/5250",
          read_restricted: false,
          permission: 1,
          notification_level: null,
          logo_url: null,
          background_url: null,
          show_subcategory_list: false,
          default_view: "latest"
        },
        {
          id: 5,
          name: "extensibility",
          color: "FE8432",
          text_color: "FFFFFF",
          slug: "extensibility",
          topic_count: 226,
          post_count: 1874,
          description:
            "Topics about extending the functionality of Discourse with plugins, themes, add-ons, or other mechanisms for extensibility.  ",
          topic_url: "/t/about-the-extensibility-category/28",
          read_restricted: false,
          permission: 1,
          notification_level: null,
          logo_url: null,
          background_url: null,
          show_subcategory_list: false,
          default_view: "latest"
        },
        {
          id: 11,
          name: "login",
          color: "edb400",
          text_color: "FFFFFF",
          slug: "login",
          topic_count: 48,
          post_count: 357,
          description:
            "Topics about logging in to Discourse, using any standard third party provider (Twitter, Facebook, Google), traditional username and password, or with a custom plugin.",
          topic_url: "/t/category-definition-for-login/2828",
          read_restricted: false,
          permission: 1,
          notification_level: null,
          logo_url: null,
          background_url: null,
          show_subcategory_list: false,
          default_view: "latest"
        },
        {
          id: 22,
          name: "plugin",
          color: "d47711",
          text_color: "FFFFFF",
          slug: "plugin",
          topic_count: 40,
          post_count: 466,
          description:
            "One post per plugin! Only plugin owners should post here. ",
          topic_url: "/t/about-the-plugin-category/12648",
          read_restricted: false,
          permission: 1,
          parent_category_id: 5,
          notification_level: null,
          logo_url: null,
          background_url: null
        },
        {
          id: 1,
          name: "bug",
          color: "e9dd00",
          text_color: "000000",
          slug: "bug",
          topic_count: 1469,
          post_count: 9295,
          description:
            "A bug report means something is broken, preventing normal/typical use of Discourse. Do be sure to search prior to submitting bugs. Include repro steps, and only describe one bug per topic please.",
          topic_url: "/t/category-definition-for-bug/2",
          read_restricted: false,
          permission: 1,
          notification_level: null,
          logo_url: null,
          background_url: null,
          can_edit: true,
          show_subcategory_list: false,
          default_view: "latest"
        },
        {
          id: 17,
          name: "uncategorized",
          color: "AB9364",
          text_color: "FFFFFF",
          slug: "uncategorized",
          topic_count: 342,
          post_count: 3090,
          description:
            "Topics that don't need a category, or don't fit into any other existing category.",
          topic_url: null,
          read_restricted: false,
          permission: 1,
          notification_level: null,
          logo_url: "",
          background_url: "",
          show_subcategory_list: false,
          default_view: "latest"
        },
        {
          id: 21,
          name: "wordpress",
          color: "1E8CBE",
          text_color: "FFFFFF",
          slug: "wordpress",
          topic_count: 26,
          post_count: 135,
          description:
            'Support for the official Discourse WordPress plugin at <a href="https://github.com/discourse/wp-discourse">https://github.com/discourse/wp-discourse</a>',
          topic_url: "/t/category-definition-for-wordpress/12282",
          read_restricted: false,
          permission: 1,
          parent_category_id: 6,
          notification_level: null,
          logo_url: null,
          background_url: null
        },
        {
          id: 8,
          name: "hosting",
          color: "74CCED",
          text_color: "FFFFFF",
          slug: "hosting",
          topic_count: 100,
          post_count: 917,
          description:
            "Topics about hosting Discourse, either on your own servers, in the cloud, or with specific hosting services.",
          topic_url: "/t/category-definition-for-hosting/2626",
          read_restricted: false,
          permission: 1,
          notification_level: null,
          logo_url: null,
          background_url: null,
          show_subcategory_list: false,
          default_view: "latest"
        },
        {
          id: 9,
          name: "ux",
          color: "5F497A",
          text_color: "FFFFFF",
          slug: "ux",
          topic_count: 452,
          post_count: 4472,
          description:
            "Discussion about the user interface of Discourse, how features are presented to the user in the client, including language and UI elements.",
          topic_url: "/t/category-definition-for-ux/2628",
          read_restricted: false,
          permission: 1,
          notification_level: null,
          logo_url: null,
          background_url: null,
          show_subcategory_list: false,
          default_view: "latest"
        },
        {
          id: 2,
          name: "feature",
          color: "0E76BD",
          text_color: "FFFFFF",
          slug: "feature",
          topic_count: 1367,
          post_count: 11942,
          description:
            "Discussion about features or potential features of Discourse: how they work, why they work, etc.",
          topic_url: "/t/category-definition-for-feature/11",
          read_restricted: false,
          permission: 1,
          notification_level: null,
          logo_url: null,
          background_url: null,
          show_subcategory_list: true,
          default_view: "latest",
          subcategory_list_style: "boxes"
        }
      ],
      post_action_types: [
        {
          name_key: "bookmark",
          name: "Bookmark",
          description: "Bookmark this post",
          short_description: "Bookmark this post",
          long_form: "bookmarked this post",
          is_flag: false,
          icon: null,
          id: 1,
          is_custom_flag: false
        },
        {
          name_key: "like",
          name: "Like",
          description: "Like this post",
          short_description: "Like this post",
          long_form: "liked this",
          is_flag: false,
          icon: "heart",
          id: 2,
          is_custom_flag: false
        },
        {
          name_key: "off_topic",
          name: "Off-Topic",
          description:
            "This post is radically off-topic in the current topic, and should probably be moved. If this is a topic, perhaps it does not belong here.",
          short_description: "Not relevant to the discussion",
          long_form: "flagged this as off-topic",
          is_flag: true,
          icon: null,
          id: 3,
          is_custom_flag: false
        },
        {
          name_key: "inappropriate",
          name: "Inappropriate",
          description:
            'This post contains content that a reasonable person would consider offensive, abusive, or a violation of <a href="/guidelines">our community guidelines</a>.',
          short_description:
            'A violation of <a href="/guidelines">our community guidelines</a>',
          long_form: "flagged this as inappropriate",
          is_flag: true,
          icon: null,
          id: 4,
          is_custom_flag: false
        },
        {
          name_key: "vote",
          name: "Vote",
          description: "Vote for this post",
          short_description: "Vote for this post",
          long_form: "voted for this post",
          is_flag: false,
          icon: null,
          id: 5,
          is_custom_flag: false
        },
        {
          name_key: "spam",
          name: "Spam",
          description:
            "This post is an advertisement. It is not useful or relevant to the current topic, but promotional in nature.",
          short_description: "This is an advertisement",
          long_form: "flagged this as spam",
          is_flag: true,
          icon: null,
          id: 8,
          is_custom_flag: false
        },
        {
          name_key: "notify_user",
          name: "Notify {{username}}",
          description:
            "This post contains something I want to talk to this person directly and privately about. Does not cast a flag.",
          short_description:
            "I want to talk to this person directly and privately about their post.",
          long_form: "notified user",
          is_flag: true,
          icon: null,
          id: 6,
          is_custom_flag: true
        },
        {
          name_key: "notify_moderators",
          name: "Notify moderators",
          description:
            'This post requires general moderator attention based on the <a href="/guidelines">guidelines</a>, <a href="/tos">TOS</a>, or for another reason not listed above.',
          short_description: "Requires staff attention for another reason",
          long_form: "notified moderators",
          is_flag: true,
          icon: null,
          id: 7,
          is_custom_flag: true
        }
      ],
      topic_flag_types: [
        {
          name_key: "inappropriate",
          name: "Inappropriate",
          description:
            'This topic contains content that a reasonable person would consider offensive, abusive, or a violation of <a href="/guidelines">our community guidelines</a>.',
          long_form: "flagged this as inappropriate",
          is_flag: true,
          icon: null,
          id: 4,
          is_custom_flag: false
        },
        {
          name_key: "spam",
          name: "Spam",
          description:
            "This topic is an advertisement. It is not useful or relevant to this site, but promotional in nature.",
          long_form: "flagged this as spam",
          is_flag: true,
          icon: null,
          id: 8,
          is_custom_flag: false
        },
        {
          name_key: "notify_moderators",
          name: "Notify moderators",
          description:
            'This topic requires general moderator attention based on the <a href="/guidelines">guidelines</a>, <a href="/tos">TOS</a>, or for another reason not listed above.',
          long_form: "notified moderators",
          is_flag: true,
          icon: null,
          id: 7,
          is_custom_flag: true
        }
      ],
      trust_levels: [
        {
          id: 0,
          name: "new user"
        },
        {
          id: 1,
          name: "basic user"
        },
        {
          id: 2,
          name: "member"
        },
        {
          id: 3,
          name: "regular"
        },
        {
          id: 4,
          name: "leader"
        }
      ],
      archetypes: [
        {
          id: "regular",
          name: "Regular Topic",
          options: []
        },
        {
          id: "banner",
          name: "translation missing: en.archetypes.banner.title",
          options: []
        }
      ]
    }
  }
};
