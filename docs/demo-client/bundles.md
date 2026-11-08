[← Reports](reports.md) · [Contents](README.md#the-pages)

# Every bundle

This page is the whole demo in one place: every bundle the demo writes, in full, in the order it writes them. The
pages before this one show the same JSON in pieces, each piece where it is explained. This page is for the other way
of reading, when you know what you are looking for and want to search for it, or want to see how much configuration
the demo comes to.

The JSON is what a running instance returns for each bundle, as the reference describes under
[Reading the JSON on these pages](reference.md#reading-the-json-on-these-pages).

## base

The client, its traits, its columns in the forms list, its shared type, its facts, and its menu entry. Explained on the
[client](client.md), [talk core details](talk-core-details.md), [abstract](abstract.md), [committee
review](committee-review.md), and [workshop logistics](workshop-logistics.md) pages.

<!-- kdr-doc: bundle=base -->
```json
{
  "kdr:clientDef": [
    {
      "audience": "internal",
      "clientId": "confdemo",
      "description": "A demo client: a conference's call for talks. Documented in docs/demo-client.",
      "name": "Conference demo",
      "preload": false,
      "staticConfig": false,
      "usageType": "demo",
      "enabledEnvironments": ["unit", "local", "dev"],
      "includedTraits": ["kdr:name"],
      "webResourcesId": "kdr:confdemo",
      "userLabels": ["committee"],
      "joinRoles": [
        {"name": "speaker", "label": "Speaker", "persona": "member", "summary": "Submit a talk, and follow it through the committee's review."},
        {"name": "organizer", "label": "Organizer", "persona": "admin", "summary": "See every talk submitted, and run the conference's workflows."},
        {
          "name": "committee",
          "label": "Committee member",
          "persona": "editor",
          "userLabels": ["committee"],
          "summary": "Review the talks whose core details are complete, and accept them."
        }
      ]
    }
  ],
  "kdr:traitDef": [
    {
      "description": "Where a talk fits in the program.",
      "traitId": "session",
      "typeName": "client.confdemo.SessionEntry",
      "appliesTo": ["formDoc"],
      "dataSchema": {
        "title": "Session",
        "properties": {
          "track": {
            "description": "The part of the program the talk belongs to.",
            "title": "Track",
            "g-options": [
              {"label": "Kotlin", "value": "kotlin"},
              {"label": "The web", "value": "web"},
              {"label": "Data", "value": "data"},
              {"label": "Workshop (hands-on)", "value": "workshop"}
            ],
            "type": "string"
          }
        },
        "required": ["track"],
        "type": "object"
      }
    },
    {
      "description": "Five questions every speaker answers.",
      "traitId": "questionnaire",
      "typeName": "client.confdemo.QuestionnaireEntry",
      "appliesTo": ["formDoc"],
      "dataSchema": {
        "title": "Questionnaire",
        "properties": {
          "firstTalk": {
            "description": "Whether this is the speaker's first talk at a conference.",
            "title": "Is this your first conference talk?",
            "type": "boolean"
          },
          "audienceLevel": {
            "description": "How much the audience needs to know already.",
            "title": "Who is the talk for?",
            "g-options": [
              {"label": "Beginners", "value": "beginner"},
              {"label": "People with some experience", "value": "intermediate"},
              {"label": "Experts", "value": "advanced"}
            ],
            "type": "string"
          },
          "takeaway": {
            "description": "What the audience leaves knowing.",
            "title": "What will the audience take away?",
            "g-longText": true,
            "type": "string"
          },
          "recordingOk": {
            "description": "Whether the talk may be recorded and published.",
            "title": "May we record the talk?",
            "type": "boolean"
          },
          "needs": {
            "description": "Anything the organizers should know for the day.",
            "title": "Anything we should know for the day?",
            "g-longText": true,
            "type": "string"
          }
        },
        "type": "object"
      }
    },
    {
      "description": "What the talk is about.",
      "traitId": "abstract",
      "typeName": "client.confdemo.AbstractEntry",
      "appliesTo": ["formDoc"],
      "dataSchema": {
        "title": "Abstract",
        "properties": {
          "summary": {
            "description": "The abstract the program will print.",
            "title": "Summary",
            "minLength": 50,
            "maxLength": 600,
            "g-longText": true,
            "g-outerWhitespace": "trim",
            "g-errors": {
              "missingRequired": "Every talk needs a summary.",
              "belowMinimum": "A summary is at least ${min} characters; give the audience a reason to come.",
              "aboveMaximum": "Keep the summary to ${max} characters; the program has limited space."
            },
            "type": "string"
          },
          "durationMinutes": {
            "description": "How long the talk runs, in minutes.",
            "title": "Duration (minutes)",
            "type": "integer",
            "minimum": 20,
            "maximum": 90,
            "g-errors": {
              "belowMinimum": "${value} minutes is shorter than any slot; a talk runs at least ${min} minutes.",
              "aboveMaximum": "${value} minutes is longer than any slot; a talk runs at most ${max} minutes.",
              "badValue": "Give the duration as a whole number of minutes, such as 45."
            }
          },
          "topics": {
            "description": "The topics the talk covers; choose up to three.",
            "title": "Topics",
            "type": "array",
            "uniqueItems": true,
            "maxItems": 3,
            "g-errors": { "aboveMaximum": "Choose at most ${max} topics, the ones the talk is most about." },
            "items": {
              "g-options": [
                {"label": "Coroutines", "value": "coroutines"},
                {"label": "Multiplatform", "value": "multiplatform"},
                {"label": "Compose", "value": "compose"},
                {"label": "Testing", "value": "testing"},
                {"label": "Tooling", "value": "tooling"}
              ]
            }
          },
          "coSpeakers": {
            "description": "Anyone presenting alongside the speaker.",
            "title": "Co-speakers",
            "type": "array",
            "items": {"$ref": "#/$defs/client.confdemo.Speaker"}
          },
          "programNote": {
            "description": "Scheduling notes for the organizers; the speaker's form leaves this out.",
            "title": "Organizer's note",
            "g-longText": true,
            "g-visibleWhen": "kdr:hasAdminLevel",
            "type": "string"
          }
        },
        "required": ["summary"],
        "type": "object"
      }
    },
    {
      "description": "The committee's review of the talk.",
      "traitId": "review",
      "typeName": "client.confdemo.ReviewEntry",
      "appliesTo": ["formDoc"],
      "dataSchema": {
        "title": "Committee review",
        "properties": {
          "score": {
            "description": "The committee's score, from 1 to 5.",
            "title": "Score",
            "type": "integer",
            "minimum": 1,
            "maximum": 5
          },
          "notes": {
            "description": "Feedback the speaker will see.",
            "title": "Notes for the speaker",
            "g-longText": true,
            "type": "string"
          }
        },
        "type": "object"
      }
    },
    {
      "description": "What a workshop needs on the day.",
      "traitId": "logistics",
      "typeName": "client.confdemo.LogisticsEntry",
      "appliesTo": ["formDoc"],
      "dataSchema": {
        "title": "Workshop logistics",
        "properties": {
          "room": {
            "description": "The room the session is in.",
            "title": "Room",
            "g-options": [
              {"label": "Main hall", "value": "hall"},
              {"label": "Track room 1", "value": "track1"},
              {"label": "Track room 2", "value": "track2"},
              {"label": "Lab A", "value": "labA"},
              {"label": "Lab B", "value": "labB"}
            ],
            "type": "string"
          },
          "needsLaptops": {
            "description": "Whether attendees bring a laptop.",
            "title": "Do attendees need their own laptop?",
            "type": "boolean"
          },
          "setup": {
            "description": "What attendees install before they arrive.",
            "title": "What should they install beforehand?",
            "g-longText": true,
            "type": "string"
          }
        },
        "if": {
          "required": ["needsLaptops"],
          "properties": {"needsLaptops": {"const": true}}
        },
        "then": {"required": ["setup"]},
        "else": {
          "not": {"required": ["setup"]}
        },
        "type": "object"
      }
    }
  ],
  "kdr:usageDef": [
    {"display": "${name}", "label": "Talk", "substring": true, "traitId": "kdr:name"},
    {"display": "${track}", "label": "Track", "substring": false, "traitId": "session"},
    {
      "columnId": "audience", "display": "${audienceLevel}", "label": "Audience", "substring": false,
      "traitId": "questionnaire"
    }
  ],
  "kdr:schemaDef": [
    {
      "typeName": "client.confdemo.Speaker",
      "schema": {
        "type": "object",
        "properties": {
          "name": {"description": "The co-speaker's name.", "title": "Name", "type": "string"},
          "email": {"description": "Where to reach them.", "title": "Email", "type": "string"}
        },
        "required": ["name"]
      }
    },
    {
      "typeName": "kdr.core.NameData",
      "schema": {
        "g-layout": {
          "$comment": "The platform's name trait, as the demo's forms show it.",
          "label": "Talk title",
          "description": "![A presentation screen](img:kdr:talkIcon){.float-left .icon width=20}The title the conference program prints, and the name the committee knows your talk by.",
          "schemaFields": [{"field": "name", "label": "Title"}]
        }
      }
    }
  ],
  "kdr:uiBlockDef": [
    {
      "blockId": "homeMenu",
      "content": {
        "menu": [{"id": "workflows", "cfactExpression": "kdr:loggedIn,kdr:app"}]
      }
    }
  ],
  "kdr:cfactDef": [
    {
      "name": "isWorkshop",
      "group": "confDemo",
      "description": "The talk's track is the workshop track; computed by the core details workflow from the session.",
      "toFrontend": false
    },
    {
      "name": "talkAccepted",
      "group": "confDemo",
      "description": "True once the committee has accepted the talk.",
      "toFrontend": false
    }
  ]
}
```

## createTalk

The creation workflow. Explained on [Creating a talk](create-talk.md).

<!-- kdr-doc: bundle=createTalk -->
```json
{
  "kdr:workflowDef": [
    {
      "workflowId": "createTalk",
      "definition": {
        "$comment": "Runs once, when a speaker starts a submission. It asks only for the title; the rest comes later.",
        "entry": "creation",
        "label": "Start a talk submission",
        "workflowId": "createTalk",
        "tasks": [
          {
            "$comment": "A creation workflow has exactly one task, with exactly one save.",
            "id": "nameTalk",
            "label": "Name your talk",
            "saves": [{"id": "create", "label": "Create the talk", "kind": "create"}],
            "traits": [{"traitId": "kdr:name", "required": true}]
          }
        ]
      }
    }
  ]
}
```

## talkCoreDetails

The core details workflow. Explained on [Talk core details](talk-core-details.md).

<!-- kdr-doc: bundle=talkCoreDetails -->
```json
{
  "kdr:workflowDef": [
    {
      "workflowId": "talkCoreDetails",
      "definition": {
        "$comment": "The form's core details workflow: what every talk needs before it is reviewed.",
        "entry": "coreDetails",
        "label": "About your talk",
        "workflowId": "talkCoreDetails",
        "functions": [
          {
            "fn": "computeCFactsFromData",
            "trait": "session",
            "op": "isInList",
            "valuePath": "track",
            "valueMapping": [{"value": "workshop", "cfact": "isWorkshop"}]
          }
        ],
        "tasks": [
          {
            "id": "describe",
            "label": "Title, track, and background",
            "$comment": "The title comes first, so a speaker can change the one they started with.",
            "saves": [{"id": "saveAbout", "label": "Save", "kind": "edit"}],
            "traits": [
              {"traitId": "kdr:name", "required": true},
              {"traitId": "session", "required": true},
              {"traitId": "questionnaire", "required": true}
            ]
          }
        ],
        "types": {
          "client.confdemo.QuestionnaireData": {
            "g-layout": {
              "$comment": "The core details workflow asks one of the five questions. The abstract workflow asks the rest.",
              "mode": "authoritative",
              "schemaFields": [{"field": "firstTalk"}]
            }
          }
        }
      }
    }
  ]
}
```

## abstract

The first normal workflow. Explained on [Abstract](abstract.md).

<!-- kdr-doc: bundle=abstract -->
```json
{
  "kdr:workflowDef": [
    {
      "workflowId": "abstract",
      "definition": {
        "$comment": "No eligibility tests: any talk can have its abstract written at any time.",
        "entry": "normal",
        "label": "Write the abstract",
        "workflowId": "abstract",
        "tasks": [
          {
            "id": "write",
            "label": "The abstract and the rest of the questionnaire",
            "saves": [{"id": "saveAbstract", "label": "Save the abstract", "kind": "edit"}],
            "traits": [{"traitId": "abstract", "required": true}, {"traitId": "questionnaire", "required": true}]
          }
        ],
        "types": {
          "client.confdemo.QuestionnaireData": {
            "g-layout": {
              "$comment": "The four questions the core details workflow left out.",
              "mode": "authoritative",
              "schemaFields": [
                {"field": "audienceLevel"},
                {"field": "takeaway"},
                {"field": "recordingOk"},
                {"field": "needs"}
              ]
            }
          }
        }
      }
    }
  ]
}
```

## review

The committee review. Explained on [Committee review](committee-review.md).

<!-- kdr-doc: bundle=review -->
```json
{
  "kdr:workflowDef": [
    {
      "workflowId": "review",
      "definition": {
        "$comment": "The committee reviews a talk once its core details are complete, then accepts it.",
        "entry": "normal",
        "label": "Committee review",
        "highlightStart": true,
        "workflowId": "review",
        "eligibility": [
          {
            "$comment": "Reviewers need the track.",
            "explanation": "The talk's core details have to be complete before the committee reviews it.",
            "id": "coreDetailsDone",
            "test": "kdr:coreDetailsComplete"
          }
        ],
        "locks": [
          {
            "overrideWhen": "kdr:hasAdminLevel",
            "traitId": "review",
            "when": "#always",
            "writableVia": "recordReview"
          }
        ],
        "singletons": [{"cfact": "kdr:finished", "when": "talkAccepted"}],
        "tasks": [
          {
            "$comment": "Only a committee member may save this task; the speaker sees it read-only.",
            "id": "recordReview",
            "keyword": "review",
            "label": "Record the review",
            "saveWhen": "kdr:reviewer",
            "functions": [{"fn": "userHasLabel", "label": "committee", "cfact": "kdr:reviewer"}],
            "saves": [{"id": "saveReview", "label": "Save the review", "kind": "edit"}],
            "traits": [{"traitId": "review", "required": true}]
          },
          {
            "id": "accept",
            "label": "Accept the talk",
            "approval": {
              "cfact": "talkAccepted",
              "prompt": "Read the review, then accept the talk for the program.",
              "button": "Accept the talk"
            },
            "display": {
              "select": [
                {"cfactExpression": "talkAccepted", "mode": "text", "text": "Accepted by ${approvedByName}."},
                {
                  "cfactExpression": "~kdr:isCta",
                  "mode": "text",
                  "text": "The review has to be recorded before the talk can be accepted.",
                  "disabled": true
                },
                {"cfactExpression": "kdr:reviewer", "mode": "default"},
                {"mode": "text", "text": "A committee member accepts the talk."}
              ]
            },
            "functions": [{"fn": "userHasLabel", "label": "committee", "cfact": "kdr:reviewer"}],
            "saves": [],
            "traits": []
          }
        ]
      }
    }
  ]
}
```

## logistics

Workshop logistics. Explained on [Workshop logistics](workshop-logistics.md).

<!-- kdr-doc: bundle=logistics -->
```json
{
  "kdr:workflowDef": [
    {
      "workflowId": "logistics",
      "definition": {
        "$comment": "For workshops only: the eligibility test asks for the fact the core details workflow computes from the track.",
        "entry": "normal",
        "label": "Workshop logistics",
        "workflowId": "logistics",
        "eligibility": [
          {
            "explanation": "Only a talk on the workshop track has logistics to plan.",
            "id": "workshopOnly",
            "test": "isWorkshop"
          }
        ],
        "tasks": [
          {
            "id": "plan",
            "label": "Plan the workshop",
            "saves": [{"id": "savePlan", "label": "Save the plan", "kind": "edit"}],
            "traits": [{"traitId": "logistics", "required": true}]
          }
        ],
        "types": {
          "client.confdemo.LogisticsData": {
            "g-layout": {
              "$comment": "Authoritative: the labels and descriptions here replace the trait's, and the room list is narrowed to the labs.",
              "fragmentFileId": "confDemo",
              "label": "%{@t(\"logistics.heading\")}",
              "mode": "authoritative",
              "schemaFields": [
                {
                  "field": "room",
                  "$comment": "This form asks for a lab, though the trait does not, so it says why in its own words.",
                  "label": "Lab",
                  "description": "%{@t(\"logistics.roomHelp\")}",
                  "required": true,
                  "errors": { "missingRequired": "Choose a lab, so attendees know where to go." },
                  "choices": [
                    {"value": "labA", "label": "Lab A (24 seats)"},
                    {"value": "labB", "label": "Lab B (40 seats)"}
                  ]
                },
                {
                  "field": "needsLaptops",
                  "label": "Laptops",
                  "description": "Say yes if attendees work on their own machines."
                },
                {
                  "field": "setup",
                  "label": "Setup instructions",
                  "description": "Sent to attendees a week before."
                }
              ]
            }
          }
        }
      }
    }
  ]
}
```

## reports

The two reports. Explained on [Reports](reports.md).

<!-- kdr-doc: bundle=reports -->
```json
{
  "kdr:reportDef": [
    {
      "reportId": "submissionsByTrack",
      "definition": {
        "$comment": "Per form, a roster of the talks. Grouped, how many talks each track has and the minutes they add up to.",
        "description": "Every talk with its track, its speaker, and where it stands. Grouped by track, how many talks there are and how long they run in all.",
        "label": "Submissions by track",
        "reportId": "submissionsByTrack",
        "columns": [
          {"columnId": "track", "label": "Track", "path": "form.session.track"},
          {"columnId": "talk", "label": "Talk", "path": "form.kdr:name.name"},
          {"columnId": "speaker", "label": "Speaker", "path": "user.name"},
          {"columnId": "status", "label": "Status", "path": "meta.formStatus"},
          {"columnId": "review", "label": "Review", "path": "workflow.review.category"},
          {
            "columnId": "minutes",
            "label": "Minutes",
            "path": "form.abstract.durationMinutes",
            "rollup": "sum"
          }
        ],
        "groupBy": ["track"]
      }
    },
    {
      "reportId": "questionnaireSummary",
      "definition": {
        "$comment": "Grouped by who the talk is for and whether it may be recorded: how many talks, what they promise, and how many have needs for the day.",
        "description": "The questionnaire across every talk: who each talk is for, whether it may be recorded, what the audience takes away, and what the organizers should know.",
        "label": "Questionnaire summary",
        "reportId": "questionnaireSummary",
        "columns": [
          {"columnId": "audience", "label": "Audience", "path": "form.questionnaire.audienceLevel"},
          {"columnId": "recording", "label": "May be recorded", "path": "form.questionnaire.recordingOk"},
          {"columnId": "firstTalk", "label": "First talk", "path": "form.questionnaire.firstTalk"},
          {
            "columnId": "takeaway",
            "label": "Takeaway",
            "path": "form.questionnaire.takeaway",
            "rollup": "list"
          },
          {
            "columnId": "needs",
            "label": "Needs for the day",
            "path": "form.questionnaire.needs",
            "rollup": "count"
          }
        ],
        "excludeEmpty": ["audience"],
        "groupBy": ["audience", "recording"]
      }
    }
  ]
}
```

This is the last page. Back to [Contents](README.md#the-pages).
