package com.example.myagent;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.jmolecules.archunit.JMoleculesDddRules;

@AnalyzeClasses(packages = "com.example.myagent")
public class DddArchTest {

    @ArchTest
    ArchRule dddArchitecture = JMoleculesDddRules.all();
}
