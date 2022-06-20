/**
 * Copyright (c) 2020-present, Goldman Sachs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { observer } from 'mobx-react-lite';
import { useEditorStore } from '../../../stores/EditorStore';
import { useState } from "react";

export const RuntimeOptions= observer(() => {
  const editorStore = useEditorStore();
  const [debugPlanOption, setDebugPlanOption] = useState(false);
  const [execPlanOption, setExecPlanOption] = useState(false);
  const [localPlanOption, setLocalPlanOption] = useState(false);

  const handleDebugOptionChange = () => {
    setDebugPlanOption(!debugPlanOption);
    editorStore.setDebugPlatformCodeGen(debugPlanOption);
  }

  const handleExecPlanOptionChange = () => {
    setExecPlanOption(!execPlanOption);
    editorStore.setExecPlan(execPlanOption);
  }

  const handleLocalPlanOptionChange = () => {
    setLocalPlanOption(!localPlanOption);
    editorStore.setShowLocalPlan(localPlanOption);
  }
  const Checkbox = (obj: { label : string; value: boolean, onChange : () => void}) => {
    let id="runtimeoptions-panel-item-".concat(obj.label);
    return (
    <div className={id}>
      <label>
        <input type="checkbox" checked={obj.value} onChange={obj.onChange}/>
        {obj.label}
      </label>
      </div>
    )
  };

  const checkboxes = [
    { label : 'Debug Platform Code Gen', value:editorStore.debugPlatformCodeGen, onChange : handleDebugOptionChange},
    { label : 'Exec Plan', value : editorStore.execPlan, onChange : handleExecPlanOptionChange},
    { label : 'Local Plan', value : editorStore.showLocalPlan, onChange : handleLocalPlanOptionChange}
  ];

  return (
    <div className="runtimeoptions-panel">
    <ul className='runtimeoptions-list'>
      {checkboxes.map(({label, value, onChange}) => {return (<Checkbox label={label} value = {value} onChange = {onChange}/>)})};
    </ul>
    </div>
  )
})


