const flowableModdle = {
  name: 'Flowable',
  uri: 'http://flowable.org/bpmn',
  prefix: 'flowable',
  xml: {tagAlias: 'lowerCase'},
  types: [
    {
      name: 'FlowableUserTask',
      extends: ['bpmn:UserTask'],
      properties: [
        {name: 'assignee', isAttr: true, type: 'String'},
        {name: 'candidateUsers', isAttr: true, type: 'String'},
        {name: 'candidateGroups', isAttr: true, type: 'String'},
        {name: 'dueDate', isAttr: true, type: 'String'},
        {name: 'priority', isAttr: true, type: 'String'},
      ],
    },
  ],
} as const;

export default flowableModdle;
