// Copyright (c) 2018 WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
//
// WSO2 Inc. licenses this file to you under the Apache License,
// Version 2.0 (the "License"); you may not use this file except
// in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
import ballerina/io;
import ballerina/grpc;

endpoint grpc:Listener ep {
    host:"localhost",
    port:9090
};

service HelloWorld bind ep {

    testInputNestedStruct(endpoint caller, Person req) {
        io:println("name: " + req.name);
        io:println(req.address);
        string message = "Submitted name: " + req.name;
        error? err = caller->send(message);
        io:println(err.message but { () => ("Server send response : " + message) });
        _ = caller->complete();
    }

    testOutputNestedStruct(endpoint caller, string name) {
        io:println("requested name: " + name);
        Person person = {name:"Sam", address:{postalCode:10300, state:"CA", country:"USA"}};
        io:println(person);
        error? err = caller->send(person);
        io:println(err.message but { () => "" });
        _ = caller->complete();
    }
}

type Person record {
    string name;
    Address address;
};

type Address record {
    int postalCode;
    string state;
    string country;
};
